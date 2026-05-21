package dev.sixik.generator_accelerator.common.biome.region;

import dev.sixik.generator_accelerator.common.biome.ClimateSamplerRaw;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;

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
 * Exact immutable 4x4-chunk quart-domain biome and climate raster.
 */
public final class GARegionalClimateQuartRaster {
    public static final int REGION_CHUNK_SHIFT = 2;
    public static final int REGION_CHUNK_SIZE = 1 << REGION_CHUNK_SHIFT;
    public static final int REGION_BLOCK_SHIFT = REGION_CHUNK_SHIFT + 4;
    public static final int REGION_QUART_SHIFT = REGION_CHUNK_SHIFT + 2;
    public static final int REGION_QUART_SIZE = 1 << REGION_QUART_SHIFT;

    private static final int CLIMATE_FIELD_COUNT = 6;
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "ga.biome.regionalClimateQuartRaster.enabled",
            "false"
    ));
    private static final boolean BIOME_MANAGER_ENABLED = Boolean.parseBoolean(System.getProperty(
            "ga.biome.regionalClimateQuartRaster.biomeManager.enabled",
            "false"
    ));
    private static final boolean SURFACE_ENABLED = Boolean.parseBoolean(System.getProperty(
            "ga.biome.regionalClimateQuartRaster.surface.enabled",
            "false"
    ));
    private static final int MAX_ENTRIES = Math.max(
            16,
            Integer.getInteger("ga.biome.regionalClimateQuartRaster.maxEntries", 96)
    );

    private static final ConcurrentHashMap<RasterKey, Future<RasterEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicLong EVICTIONS = new AtomicLong();
    private static final AtomicLong WAITS = new AtomicLong();

    private GARegionalClimateQuartRaster() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static int quartCount(int blockHeight) {
        return Math.max(1, (blockHeight + 3) >> 2);
    }

    public static boolean surfaceEnabled() {
        return ENABLED && SURFACE_ENABLED;
    }

    public static View view(
            GARegionalClimateQuartRasterOwner owner,
            int chunkMinX,
            int chunkMinZ
    ) {
        if (!ENABLED || owner == null || owner.biomeSource() == null || owner.quartHeight() <= 0) {
            return null;
        }
        return viewForRegion(
                owner,
                chunkMinX >> REGION_BLOCK_SHIFT,
                chunkMinZ >> REGION_BLOCK_SHIFT
        );
    }

    public static View viewForRegion(
            GARegionalClimateQuartRasterOwner owner,
            int regionChunkX,
            int regionChunkZ
    ) {
        if (!ENABLED || owner == null || owner.biomeSource() == null || owner.quartHeight() <= 0) {
            return null;
        }
        RasterKey key = new RasterKey(owner, regionChunkX, regionChunkZ);
        Future<RasterEntry> future = CACHE.get(key);
        if (future == null) {
            MISSES.incrementAndGet();
            FutureTask<RasterEntry> task = new FutureTask<>(() -> {
                BUILDS.incrementAndGet();
                return buildEntry(owner, regionChunkX, regionChunkZ);
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
            HITS.incrementAndGet();
        }
        return new View(regionChunkX, regionChunkZ, awaitEntry(key, future));
    }

    public static Holder<Biome> sampleBiome(
            BiomeManager.NoiseBiomeSource source,
            int quartX,
            int quartY,
            int quartZ
    ) {
        if (!ENABLED || !BIOME_MANAGER_ENABLED || source == null) {
            return source == null ? null : source.getNoiseBiome(quartX, quartY, quartZ);
        }
        GARegionalClimateQuartRasterOwner owner = new GARegionalClimateQuartRasterOwner(
                source,
                source,
                null,
                null,
                quartY,
                1
        );
        View view = viewForRegion(owner, quartX >> REGION_QUART_SHIFT, quartZ >> REGION_QUART_SHIFT);
        if (view == null) {
            return source.getNoiseBiome(quartX, quartY, quartZ);
        }
        Holder<Biome> biome = view.sampleNoiseBiome(quartX, quartY, quartZ);
        return biome != null ? biome : source.getNoiseBiome(quartX, quartY, quartZ);
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("maxEntries", MAX_ENTRIES);
        out.put("entries", ENTRY_COUNT.get());
        out.put("hits", HITS.get());
        out.put("misses", MISSES.get());
        out.put("builds", BUILDS.get());
        out.put("evictions", EVICTIONS.get());
        out.put("waits", WAITS.get());

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
        HITS.set(0L);
        MISSES.set(0L);
        BUILDS.set(0L);
        EVICTIONS.set(0L);
        WAITS.set(0L);
    }

    private static RasterEntry buildEntry(
            GARegionalClimateQuartRasterOwner owner,
            int regionChunkX,
            int regionChunkZ
    ) {
        int quartHeight = owner.quartHeight();
        int planeSize = REGION_QUART_SIZE * REGION_QUART_SIZE;
        int totalSamples = quartHeight * planeSize;
        int baseQuartX = regionChunkX << REGION_QUART_SHIFT;
        int baseQuartZ = regionChunkZ << REGION_QUART_SHIFT;

        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = new Holder[totalSamples];
        long[] climate = owner.hasClimate() ? new long[totalSamples * CLIMATE_FIELD_COUNT] : null;
        long[] scratch = climate == null ? null : new long[CLIMATE_FIELD_COUNT];

        BiomeManager.NoiseBiomeSource biomeSource = owner.biomeSource();
        Climate.Sampler climateSampler = owner.climateSampler();
        Object climateSamplerObject = climateSampler;
        ClimateSamplerRaw rawSampler = climateSamplerObject instanceof ClimateSamplerRaw raw ? raw : null;
        int minQuartY = owner.minQuartY();

        for (int localQuartY = 0; localQuartY < quartHeight; localQuartY++) {
            int quartY = minQuartY + localQuartY;
            int yBase = localQuartY * planeSize;
            for (int localQuartZ = 0; localQuartZ < REGION_QUART_SIZE; localQuartZ++) {
                int quartZ = baseQuartZ + localQuartZ;
                int zBase = yBase + localQuartZ * REGION_QUART_SIZE;
                for (int localQuartX = 0; localQuartX < REGION_QUART_SIZE; localQuartX++) {
                    int quartX = baseQuartX + localQuartX;
                    int index = zBase + localQuartX;
                    biomes[index] = biomeSource.getNoiseBiome(quartX, quartY, quartZ);
                    if (climate == null) {
                        continue;
                    }

                    int climateBase = index * CLIMATE_FIELD_COUNT;
                    if (rawSampler != null) {
                        rawSampler.ga$sampleRaw(quartX, quartY, quartZ, scratch);
                    } else {
                        Climate.TargetPoint targetPoint = climateSampler.sample(quartX, quartY, quartZ);
                        scratch[0] = targetPoint.temperature();
                        scratch[1] = targetPoint.humidity();
                        scratch[2] = targetPoint.continentalness();
                        scratch[3] = targetPoint.erosion();
                        scratch[4] = targetPoint.depth();
                        scratch[5] = targetPoint.weirdness();
                    }
                    System.arraycopy(scratch, 0, climate, climateBase, CLIMATE_FIELD_COUNT);
                }
            }
        }

        return new RasterEntry(regionChunkX, regionChunkZ, minQuartY, quartHeight, biomes, climate);
    }

    private static RasterEntry awaitEntry(RasterKey key, Future<RasterEntry> future) {
        try {
            WAITS.incrementAndGet();
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional climate quart raster interrupted", interrupted);
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
            throw new IllegalStateException("regional climate quart raster failed", cause);
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
                EVICTIONS.incrementAndGet();
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

        public Holder<Biome> sample(int blockX, int blockY, int blockZ) {
            return this.sampleNoiseBiome(
                    QuartPos.fromBlock(blockX),
                    QuartPos.fromBlock(blockY),
                    QuartPos.fromBlock(blockZ)
            );
        }

        public Holder<Biome> sampleNoiseBiome(int quartX, int quartY, int quartZ) {
            int localQuartX = quartX - (this.regionChunkX << REGION_QUART_SHIFT);
            int localQuartZ = quartZ - (this.regionChunkZ << REGION_QUART_SHIFT);
            if (localQuartX < 0 || localQuartX >= REGION_QUART_SIZE || localQuartZ < 0 || localQuartZ >= REGION_QUART_SIZE) {
                return null;
            }
            int clampedQuartY = Mth.clamp(
                    quartY,
                    this.entry.minQuartY,
                    this.entry.minQuartY + this.entry.quartHeight - 1
            );
            int localQuartY = clampedQuartY - this.entry.minQuartY;
            int planeSize = REGION_QUART_SIZE * REGION_QUART_SIZE;
            return this.entry.biomes[localQuartY * planeSize + localQuartZ * REGION_QUART_SIZE + localQuartX];
        }

        public long[] sampleClimateRaw(int quartX, int quartY, int quartZ, long[] out) {
            if (out == null || out.length < CLIMATE_FIELD_COUNT || this.entry.climate == null) {
                return null;
            }
            int localQuartX = quartX - (this.regionChunkX << REGION_QUART_SHIFT);
            int localQuartZ = quartZ - (this.regionChunkZ << REGION_QUART_SHIFT);
            if (localQuartX < 0 || localQuartX >= REGION_QUART_SIZE || localQuartZ < 0 || localQuartZ >= REGION_QUART_SIZE) {
                return null;
            }
            int clampedQuartY = Mth.clamp(
                    quartY,
                    this.entry.minQuartY,
                    this.entry.minQuartY + this.entry.quartHeight - 1
            );
            int localQuartY = clampedQuartY - this.entry.minQuartY;
            int planeSize = REGION_QUART_SIZE * REGION_QUART_SIZE;
            int sampleIndex = localQuartY * planeSize + localQuartZ * REGION_QUART_SIZE + localQuartX;
            System.arraycopy(this.entry.climate, sampleIndex * CLIMATE_FIELD_COUNT, out, 0, CLIMATE_FIELD_COUNT);
            return out;
        }

        public void prewarmChunk() {
            // The entry is immutable and already materialized when the view exists.
        }
    }

    private record RasterEntry(
            int regionChunkX,
            int regionChunkZ,
            int minQuartY,
            int quartHeight,
            Holder<Biome>[] biomes,
            long[] climate
    ) {
        private long approximateHeapBytes() {
            long bytes = 32L + (long) this.biomes.length * 8L;
            if (this.climate != null) {
                bytes += 32L + (long) this.climate.length * Long.BYTES;
            }
            return bytes;
        }
    }

    private record EvictionEntry(RasterKey key, Future<RasterEntry> future) {
    }

    private static final class RasterKey {
        private final GARegionalClimateQuartRasterOwner owner;
        private final int regionChunkX;
        private final int regionChunkZ;
        private final int hash;

        private RasterKey(GARegionalClimateQuartRasterOwner owner, int regionChunkX, int regionChunkZ) {
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
