package dev.sixik.generator_accelerator.common.aquifer.region;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Exact 4x4 region atlas for aquifer sample points and global fluid lookups.
 *
 * <p>The atlas uses immutable per-region handles and deduplicated entry builds.
 * Individual sample/global entries are filled lazily so callers can prewarm the
 * region ahead of the hot path or synchronously materialize exact values on the
 * first miss without changing worldgen semantics.</p>
 */
public final class GARegionalAquiferAtlas {
    public static final int REGION_CHUNK_SHIFT = 2;
    public static final int REGION_BLOCK_SHIFT = REGION_CHUNK_SHIFT + 4;
    public static final int REGION_BLOCK_SIZE = 1 << REGION_BLOCK_SHIFT;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.aquifer.regionalAtlas.enabled", "true")
    );
    private static final int MAX_REGIONS = Math.max(
            16,
            Integer.getInteger("ga.aquifer.regionalAtlas.maxRegions", 256)
    );

    private static final ConcurrentHashMap<RegionKey, Future<RegionEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger REGION_COUNT = new AtomicInteger();

    private static final AtomicLong REGION_HITS = new AtomicLong();
    private static final AtomicLong REGION_MISSES = new AtomicLong();
    private static final AtomicLong REGION_BUILDS = new AtomicLong();
    private static final AtomicLong REGION_EVICTIONS = new AtomicLong();
    private static final AtomicLong REGION_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong SAMPLE_HITS = new AtomicLong();
    private static final AtomicLong SAMPLE_MISSES = new AtomicLong();
    private static final AtomicLong SAMPLE_BUILDS = new AtomicLong();
    private static final AtomicLong GLOBAL_HITS = new AtomicLong();
    private static final AtomicLong GLOBAL_MISSES = new AtomicLong();
    private static final AtomicLong GLOBAL_BUILDS = new AtomicLong();

    private GARegionalAquiferAtlas() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static View view(GARegionalAquiferAtlasOwner owner, int blockX, int blockZ) {
        if (!ENABLED || owner == null) {
            return View.disabled(owner, blockX >> REGION_BLOCK_SHIFT, blockZ >> REGION_BLOCK_SHIFT);
        }
        int regionX = blockX >> REGION_BLOCK_SHIFT;
        int regionZ = blockZ >> REGION_BLOCK_SHIFT;
        return new View(owner, regionX, regionZ, regionEntry(owner, regionX, regionZ));
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("maxRegions", MAX_REGIONS);
        out.put("regions", REGION_COUNT.get());
        out.put("regionHits", REGION_HITS.get());
        out.put("regionMisses", REGION_MISSES.get());
        out.put("regionBuilds", REGION_BUILDS.get());
        out.put("regionEvictions", REGION_EVICTIONS.get());
        out.put("regionWaitNanos", REGION_WAIT_NANOS.get());
        out.put("sampleHits", SAMPLE_HITS.get());
        out.put("sampleMisses", SAMPLE_MISSES.get());
        out.put("sampleBuilds", SAMPLE_BUILDS.get());
        out.put("globalHits", GLOBAL_HITS.get());
        out.put("globalMisses", GLOBAL_MISSES.get());
        out.put("globalBuilds", GLOBAL_BUILDS.get());
        long approxBytes = 0L;
        for (Future<RegionEntry> future : CACHE.values()) {
            if (future.isDone()) {
                try {
                    approxBytes += future.get().approximateHeapBytes();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ignored) {
                }
            }
        }
        out.put("approximateHeapBytes", approxBytes);
        return out;
    }

    static void clearForTests() {
        CACHE.clear();
        INSERTION_ORDER.clear();
        REGION_COUNT.set(0);
        REGION_HITS.set(0L);
        REGION_MISSES.set(0L);
        REGION_BUILDS.set(0L);
        REGION_EVICTIONS.set(0L);
        REGION_WAIT_NANOS.set(0L);
        SAMPLE_HITS.set(0L);
        SAMPLE_MISSES.set(0L);
        SAMPLE_BUILDS.set(0L);
        GLOBAL_HITS.set(0L);
        GLOBAL_MISSES.set(0L);
        GLOBAL_BUILDS.set(0L);
    }

    private static RegionEntry regionEntry(GARegionalAquiferAtlasOwner owner, int regionX, int regionZ) {
        RegionKey key = new RegionKey(owner, regionX, regionZ);
        Future<RegionEntry> future = CACHE.get(key);
        if (future == null) {
            REGION_MISSES.incrementAndGet();
            FutureTask<RegionEntry> task = new FutureTask<>(() -> {
                REGION_BUILDS.incrementAndGet();
                return new RegionEntry();
            });
            Future<RegionEntry> existing = CACHE.putIfAbsent(key, task);
            if (existing == null) {
                future = task;
                INSERTION_ORDER.offer(new EvictionEntry(key, task));
                REGION_COUNT.incrementAndGet();
                task.run();
                evictIfNeeded();
            } else {
                future = existing;
            }
        } else {
            REGION_HITS.incrementAndGet();
        }
        return awaitEntry(key, future);
    }

    private static RegionEntry awaitEntry(RegionKey key, Future<RegionEntry> future) {
        long waitStart = System.nanoTime();
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                REGION_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional aquifer atlas interrupted", interrupted);
        } catch (ExecutionException failure) {
            if (CACHE.remove(key, future)) {
                REGION_COUNT.decrementAndGet();
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("regional aquifer atlas failed", cause);
        } finally {
            REGION_WAIT_NANOS.addAndGet(System.nanoTime() - waitStart);
        }
    }

    private static void evictIfNeeded() {
        while (REGION_COUNT.get() > MAX_REGIONS) {
            EvictionEntry oldest = INSERTION_ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (CACHE.remove(oldest.key(), oldest.future())) {
                REGION_COUNT.decrementAndGet();
                REGION_EVICTIONS.incrementAndGet();
            }
        }
    }

    public record Sample(int fluidLevel, byte fluidKind, int blockId) {
        public static final Sample FALLBACK = new Sample(Integer.MIN_VALUE, (byte) 0, Integer.MIN_VALUE);
    }

    public static final class View {
        private final GARegionalAquiferAtlasOwner owner;
        private final int regionX;
        private final int regionZ;
        private final RegionEntry entry;
        private final boolean enabled;

        private View(GARegionalAquiferAtlasOwner owner, int regionX, int regionZ, RegionEntry entry) {
            this.owner = owner;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.entry = entry;
            this.enabled = true;
        }

        private static View disabled(GARegionalAquiferAtlasOwner owner, int regionX, int regionZ) {
            return new View(owner, regionX, regionZ, null, false);
        }

        private View(GARegionalAquiferAtlasOwner owner, int regionX, int regionZ, RegionEntry entry, boolean enabled) {
            this.owner = owner;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.entry = entry;
            this.enabled = enabled;
        }

        public boolean enabled() {
            return this.enabled;
        }

        public int regionX() {
            return this.regionX;
        }

        public int regionZ() {
            return this.regionZ;
        }

        public Sample samplePoint(int sampleX, int sampleY, int sampleZ, Supplier<Sample> builder) {
            if (!this.enabled || this.entry == null || !isRegionCoord(sampleX, sampleZ)) {
                return builder.get();
            }
            long key = BlockPos.asLong(sampleX, sampleY, sampleZ);
            Future<Sample> future = this.entry.samplePoints.get(key);
            if (future != null) {
                SAMPLE_HITS.incrementAndGet();
                return awaitValue(future, "sample", this.entry.samplePoints, key);
            }
            SAMPLE_MISSES.incrementAndGet();
            FutureTask<Sample> task = new FutureTask<>(() -> {
                SAMPLE_BUILDS.incrementAndGet();
                Sample sample = builder.get();
                return sample == null ? Sample.FALLBACK : sample;
            });
            Future<Sample> existing = this.entry.samplePoints.putIfAbsent(key, task);
            if (existing == null) {
                task.run();
                return awaitValue(task, "sample", this.entry.samplePoints, key);
            }
            SAMPLE_HITS.incrementAndGet();
            return awaitValue(existing, "sample", this.entry.samplePoints, key);
        }

        public Sample globalFluid(int blockX, int blockY, int blockZ, Supplier<Sample> builder) {
            if (!this.enabled || this.entry == null || !isRegionCoord(blockX, blockZ)) {
                return builder.get();
            }
            long key = BlockPos.asLong(blockX, blockY, blockZ);
            Future<Sample> future = this.entry.globalFluids.get(key);
            if (future != null) {
                GLOBAL_HITS.incrementAndGet();
                return awaitValue(future, "global fluid", this.entry.globalFluids, key);
            }
            GLOBAL_MISSES.incrementAndGet();
            FutureTask<Sample> task = new FutureTask<>(() -> {
                GLOBAL_BUILDS.incrementAndGet();
                Sample sample = builder.get();
                return sample == null ? Sample.FALLBACK : sample;
            });
            Future<Sample> existing = this.entry.globalFluids.putIfAbsent(key, task);
            if (existing == null) {
                task.run();
                return awaitValue(task, "global fluid", this.entry.globalFluids, key);
            }
            GLOBAL_HITS.incrementAndGet();
            return awaitValue(existing, "global fluid", this.entry.globalFluids, key);
        }

        public void prewarmSamplePoint(int sampleX, int sampleY, int sampleZ, Supplier<Sample> builder) {
            samplePoint(sampleX, sampleY, sampleZ, builder);
        }

        public void prewarmGlobalFluid(int blockX, int blockY, int blockZ, Supplier<Sample> builder) {
            globalFluid(blockX, blockY, blockZ, builder);
        }

        public byte globalFluidKindAt(int blockX, int blockY, int blockZ, Supplier<Sample> builder) {
            Sample sample = globalFluid(blockX, blockY, blockZ, builder);
            return blockY < sample.fluidLevel() ? sample.fluidKind() : 0;
        }

        public int globalFluidLevelAt(int blockX, int blockY, int blockZ, Supplier<Sample> builder) {
            return globalFluid(blockX, blockY, blockZ, builder).fluidLevel();
        }

        public int globalFluidBlockIdAt(int blockX, int blockY, int blockZ, Supplier<Sample> builder) {
            return globalFluid(blockX, blockY, blockZ, builder).blockId();
        }

        private boolean isRegionCoord(int blockX, int blockZ) {
            return (blockX >> REGION_BLOCK_SHIFT) == this.regionX
                    && (blockZ >> REGION_BLOCK_SHIFT) == this.regionZ;
        }
    }

    private static <T> T awaitValue(
            Future<T> future,
            String label,
            ConcurrentHashMap<Long, Future<T>> cache,
            long key
    ) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cache.remove(key, future);
            throw new IllegalStateException("regional aquifer atlas " + label + " interrupted", interrupted);
        } catch (ExecutionException failure) {
            cache.remove(key, future);
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("regional aquifer atlas " + label + " failed", cause);
        }
    }

    private static final class RegionEntry {
        private final ConcurrentHashMap<Long, Future<Sample>> samplePoints = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Future<Sample>> globalFluids = new ConcurrentHashMap<>();

        private long approximateHeapBytes() {
            return 96L * (long) this.samplePoints.size() + 96L * (long) this.globalFluids.size();
        }
    }

    private record EvictionEntry(RegionKey key, Future<RegionEntry> future) {
    }

    private static final class RegionKey {
        private final GARegionalAquiferAtlasOwner owner;
        private final int regionX;
        private final int regionZ;
        private final int hash;

        private RegionKey(GARegionalAquiferAtlasOwner owner, int regionX, int regionZ) {
            this.owner = owner;
            this.regionX = regionX;
            this.regionZ = regionZ;

            int result = owner.hashCode();
            result = 31 * result + regionX;
            result = 31 * result + regionZ;
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RegionKey that)) {
                return false;
            }
            return this.regionX == that.regionX
                    && this.regionZ == that.regionZ
                    && this.owner.equals(that.owner);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
