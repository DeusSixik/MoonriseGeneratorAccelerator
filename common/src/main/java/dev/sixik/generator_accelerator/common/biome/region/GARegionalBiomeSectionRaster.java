package dev.sixik.generator_accelerator.common.biome.region;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exact 4x4 chunk quart-biome raster built from already generated chunk sections.
 *
 * <p>This keeps detached decoration and other quart-domain consumers on a shared,
 * immutable region snapshot instead of materializing one biome array per chunk.</p>
 */
public final class GARegionalBiomeSectionRaster {
    public static final int REGION_CHUNK_SHIFT = 2;
    public static final int REGION_CHUNK_SIZE = 1 << REGION_CHUNK_SHIFT;
    public static final int REGION_BLOCK_SHIFT = REGION_CHUNK_SHIFT + 4;
    public static final int REGION_QUART_SHIFT = REGION_CHUNK_SHIFT + 2;
    public static final int REGION_QUART_SIZE = 1 << REGION_QUART_SHIFT;
    public static final int REGION_QUART_MASK = REGION_QUART_SIZE - 1;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.biome.regionalQuartRaster.enabled", "false")
    );
    private static final int MAX_ENTRIES = Math.max(
            16,
            Integer.getInteger("ga.biome.regionalQuartRaster.maxEntries", 96)
    );

    private static final ConcurrentHashMap<RasterKey, Future<RasterEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();

    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong CACHE_BUILDS = new AtomicLong();
    private static final AtomicLong CACHE_EVICTIONS = new AtomicLong();

    private GARegionalBiomeSectionRaster() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static View capture(
            int regionChunkX,
            int regionChunkZ,
            ChunkAccess[] chunks,
            int minBuildHeight,
            int buildHeight
    ) {
        if (!ENABLED || chunks == null || chunks.length != REGION_CHUNK_SIZE * REGION_CHUNK_SIZE) {
            return null;
        }
        GARegionalBiomeSectionRasterOwner owner = new GARegionalBiomeSectionRasterOwner(chunks, minBuildHeight, buildHeight);
        RasterKey key = new RasterKey(owner, regionChunkX, regionChunkZ);
        Future<RasterEntry> future = CACHE.get(key);
        if (future == null) {
            CACHE_MISSES.incrementAndGet();
            FutureTask<RasterEntry> task = new FutureTask<>(() -> {
                CACHE_BUILDS.incrementAndGet();
                return buildEntry(regionChunkX, regionChunkZ, chunks, minBuildHeight, buildHeight);
            });
            Future<RasterEntry> existing = CACHE.putIfAbsent(key, task);
            if (existing == null) {
                future = task;
                INSERTION_ORDER.offer(new EvictionEntry(key, task));
                ENTRY_COUNT.incrementAndGet();
                task.run();
                evictIfNeeded();
            } else {
                future = existing;
            }
        } else {
            CACHE_HITS.incrementAndGet();
        }
        return new View(regionChunkX, regionChunkZ, awaitEntry(key, future));
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("maxEntries", MAX_ENTRIES);
        out.put("entries", ENTRY_COUNT.get());
        out.put("hits", CACHE_HITS.get());
        out.put("misses", CACHE_MISSES.get());
        out.put("builds", CACHE_BUILDS.get());
        out.put("evictions", CACHE_EVICTIONS.get());
        long approxBytes = 0L;
        for (Future<RasterEntry> future : CACHE.values()) {
            if (!future.isDone()) {
                continue;
            }
            try {
                approxBytes += future.get().approximateHeapBytes();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ignored) {
            }
        }
        out.put("approximateHeapBytes", approxBytes);
        return out;
    }

    static void clearForTests() {
        CACHE.clear();
        INSERTION_ORDER.clear();
        ENTRY_COUNT.set(0);
        CACHE_HITS.set(0L);
        CACHE_MISSES.set(0L);
        CACHE_BUILDS.set(0L);
        CACHE_EVICTIONS.set(0L);
    }

    private static RasterEntry buildEntry(
            int regionChunkX,
            int regionChunkZ,
            ChunkAccess[] chunks,
            int minBuildHeight,
            int buildHeight
    ) {
        int minQuartY = QuartPos.fromBlock(minBuildHeight);
        int quartHeight = Math.max(1, QuartPos.fromBlock(buildHeight));
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = new Holder[quartHeight * REGION_QUART_SIZE * REGION_QUART_SIZE];
        for (int localChunkZ = 0; localChunkZ < REGION_CHUNK_SIZE; localChunkZ++) {
            for (int localChunkX = 0; localChunkX < REGION_CHUNK_SIZE; localChunkX++) {
                ChunkAccess chunk = chunks[localChunkX | (localChunkZ << REGION_CHUNK_SHIFT)];
                if (chunk == null) {
                    continue;
                }
                copyChunkBiomesIntoRegion(
                        biomes,
                        chunk,
                        localChunkX,
                        localChunkZ,
                        minQuartY,
                        quartHeight
                );
            }
        }
        return new RasterEntry(regionChunkX, regionChunkZ, minQuartY, quartHeight, biomes);
    }

    private static void copyChunkBiomesIntoRegion(
            Holder<Biome>[] regionBiomes,
            ChunkAccess chunk,
            int localChunkX,
            int localChunkZ,
            int minQuartY,
            int quartHeight
    ) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseQuartX = localChunkX << 2;
        int baseQuartZ = localChunkZ << 2;
        for (int localQuartY = 0; localQuartY < quartHeight; localQuartY++) {
            int quartY = minQuartY + localQuartY;
            int sectionIndex = Math.floorDiv(QuartPos.toBlock(quartY), 16) - chunk.getMinSection();
            LevelChunkSection section = sectionIndex < 0 || sectionIndex >= sections.length ? null : sections[sectionIndex];
            if (section == null) {
                continue;
            }
            int srcY = quartY & 3;
            int dstYBase = localQuartY * REGION_QUART_SIZE * REGION_QUART_SIZE;
            for (int localQuartZ = 0; localQuartZ < 4; localQuartZ++) {
                int dstZBase = dstYBase + ((baseQuartZ + localQuartZ) * REGION_QUART_SIZE);
                for (int localQuartX = 0; localQuartX < 4; localQuartX++) {
                    regionBiomes[dstZBase + baseQuartX + localQuartX] = section.getNoiseBiome(localQuartX, srcY, localQuartZ);
                }
            }
        }
    }

    private static RasterEntry awaitEntry(RasterKey key, Future<RasterEntry> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional biome quart raster interrupted", interrupted);
        } catch (ExecutionException failure) {
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("regional biome quart raster failed", cause);
        }
    }

    private static void evictIfNeeded() {
        while (ENTRY_COUNT.get() > MAX_ENTRIES) {
            EvictionEntry oldest = INSERTION_ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (CACHE.remove(oldest.key(), oldest.future())) {
                ENTRY_COUNT.decrementAndGet();
                CACHE_EVICTIONS.incrementAndGet();
            }
        }
    }

    public static final class View {
        private final int regionChunkX;
        private final int regionChunkZ;
        private final RasterEntry entry;

        private View(int regionChunkX, int regionChunkZ, RasterEntry entry) {
            this.regionChunkX = regionChunkX;
            this.regionChunkZ = regionChunkZ;
            this.entry = entry;
        }

        public int regionChunkX() {
            return this.regionChunkX;
        }

        public int regionChunkZ() {
            return this.regionChunkZ;
        }

        public Holder<Biome> sample(int blockX, int blockY, int blockZ) {
            int quartX = QuartPos.fromBlock(blockX);
            int quartZ = QuartPos.fromBlock(blockZ);
            int quartY = Mth.clamp(
                    QuartPos.fromBlock(blockY),
                    this.entry.minQuartY,
                    this.entry.minQuartY + this.entry.quartHeight - 1
            );
            int localQuartX = quartX - (this.regionChunkX << 2);
            int localQuartZ = quartZ - (this.regionChunkZ << 2);
            if (localQuartX < 0 || localQuartX >= REGION_QUART_SIZE || localQuartZ < 0 || localQuartZ >= REGION_QUART_SIZE) {
                return null;
            }
            int localQuartY = quartY - this.entry.minQuartY;
            return this.entry.biomes[(localQuartY * REGION_QUART_SIZE * REGION_QUART_SIZE)
                    + (localQuartZ * REGION_QUART_SIZE)
                    + localQuartX];
        }

        public void copyChunkBiomes(int chunkX, int chunkZ, Holder<Biome>[] out) {
            int localChunkX = chunkX - this.regionChunkX;
            int localChunkZ = chunkZ - this.regionChunkZ;
            if (localChunkX < 0 || localChunkX >= REGION_CHUNK_SIZE || localChunkZ < 0 || localChunkZ >= REGION_CHUNK_SIZE) {
                throw new IllegalArgumentException("chunk is outside raster region");
            }
            int baseQuartX = localChunkX << 2;
            int baseQuartZ = localChunkZ << 2;
            int outIndex = 0;
            for (int localQuartY = 0; localQuartY < this.entry.quartHeight; localQuartY++) {
                int srcYBase = localQuartY * REGION_QUART_SIZE * REGION_QUART_SIZE;
                for (int localQuartZ = 0; localQuartZ < 4; localQuartZ++) {
                    int srcBase = srcYBase + ((baseQuartZ + localQuartZ) * REGION_QUART_SIZE) + baseQuartX;
                    for (int localQuartX = 0; localQuartX < 4; localQuartX++) {
                        out[outIndex++] = this.entry.biomes[srcBase + localQuartX];
                    }
                }
            }
        }
    }

    private record RasterEntry(
            int regionChunkX,
            int regionChunkZ,
            int minQuartY,
            int quartHeight,
            Holder<Biome>[] biomes
    ) {
        private long approximateHeapBytes() {
            return 32L + (long) this.biomes.length * 8L;
        }
    }

    private record EvictionEntry(RasterKey key, Future<RasterEntry> future) {
    }

    private static final class RasterKey {
        private final GARegionalBiomeSectionRasterOwner owner;
        private final int regionChunkX;
        private final int regionChunkZ;
        private final int hash;

        private RasterKey(GARegionalBiomeSectionRasterOwner owner, int regionChunkX, int regionChunkZ) {
            this.owner = owner;
            this.regionChunkX = regionChunkX;
            this.regionChunkZ = regionChunkZ;

            int result = owner.hashCode();
            result = 31 * result + regionChunkX;
            result = 31 * result + regionChunkZ;
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RasterKey that)) {
                return false;
            }
            return this.regionChunkX == that.regionChunkX
                    && this.regionChunkZ == that.regionChunkZ
                    && this.owner.equals(that.owner);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
