package dev.sixik.generator_accelerator.common.surface.region;

import net.minecraft.world.level.levelgen.SurfaceSystem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact 4x4-chunk regional cache for surface depth and secondary surface
 * columns. Values are computed once per immutable SurfaceSystem identity and
 * copied into chunk-local arrays without changing surface semantics.
 */
public final class GARegionalSurfaceColumnCache {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.surface.regionalColumnCache.enabled", "false")
    );
    private static final int MAX_ENTRIES = Math.max(
            16,
            Integer.getInteger("ga.surface.regionalColumnCache.maxEntries", 128)
    );

    private static final ConcurrentHashMap<RegionKey, Future<DepthEntry>> DEPTH_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RegionKey, Future<SecondaryEntry>> SECONDARY_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry<DepthEntry>> DEPTH_ORDER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<EvictionEntry<SecondaryEntry>> SECONDARY_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger DEPTH_COUNT = new AtomicInteger();
    private static final AtomicInteger SECONDARY_COUNT = new AtomicInteger();

    private GARegionalSurfaceColumnCache() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void copySurfaceDepths(SurfaceSystem surfaceSystem, int chunkMinX, int chunkMinZ, int[] out) {
        if (!ENABLED) {
            for (int xz = 0; xz < 256; xz++) {
                out[xz] = surfaceSystem.getSurfaceDepth(chunkMinX + (xz & 15), chunkMinZ + (xz >> 4));
            }
            return;
        }
        int[] values = depthRegionValues(surfaceSystem, chunkMinX >> 6, chunkMinZ >> 6);
        copyRegionInts(values, chunkMinX, chunkMinZ, out);
    }

    public static void copySecondarySurfaceNoises(SurfaceSystem surfaceSystem, int chunkMinX, int chunkMinZ, double[] out) {
        if (!ENABLED) {
            for (int xz = 0; xz < 256; xz++) {
                out[xz] = surfaceSystem.getSurfaceSecondary(chunkMinX + (xz & 15), chunkMinZ + (xz >> 4));
            }
            return;
        }
        double[] values = secondaryRegionValues(surfaceSystem, chunkMinX >> 6, chunkMinZ >> 6);
        copyRegionDoubles(values, chunkMinX, chunkMinZ, out);
    }

    static void clearForTests() {
        DEPTH_CACHE.clear();
        SECONDARY_CACHE.clear();
        DEPTH_ORDER.clear();
        SECONDARY_ORDER.clear();
        DEPTH_COUNT.set(0);
        SECONDARY_COUNT.set(0);
    }

    static int depthCacheSize() {
        return DEPTH_COUNT.get();
    }

    static int secondaryCacheSize() {
        return SECONDARY_COUNT.get();
    }

    static int[] depthRegionValues(SurfaceSystem surfaceSystem, int regionX, int regionZ) {
        RegionKey key = new RegionKey(surfaceSystem, regionX, regionZ);
        Future<DepthEntry> future = DEPTH_CACHE.get(key);
        if (future == null) {
            FutureTask<DepthEntry> task = new FutureTask<>(() -> new DepthEntry(buildDepthValues(surfaceSystem, regionX, regionZ)));
            Future<DepthEntry> existing = DEPTH_CACHE.putIfAbsent(key, task);
            if (existing == null) {
                future = task;
                DEPTH_ORDER.offer(new EvictionEntry<>(key, task));
                DEPTH_COUNT.incrementAndGet();
                task.run();
                evictDepthIfNeeded();
            } else {
                future = existing;
            }
        }
        return awaitDepth(key, future);
    }

    static double[] secondaryRegionValues(SurfaceSystem surfaceSystem, int regionX, int regionZ) {
        RegionKey key = new RegionKey(surfaceSystem, regionX, regionZ);
        Future<SecondaryEntry> future = SECONDARY_CACHE.get(key);
        if (future == null) {
            FutureTask<SecondaryEntry> task = new FutureTask<>(() -> new SecondaryEntry(buildSecondaryValues(surfaceSystem, regionX, regionZ)));
            Future<SecondaryEntry> existing = SECONDARY_CACHE.putIfAbsent(key, task);
            if (existing == null) {
                future = task;
                SECONDARY_ORDER.offer(new EvictionEntry<>(key, task));
                SECONDARY_COUNT.incrementAndGet();
                task.run();
                evictSecondaryIfNeeded();
            } else {
                future = existing;
            }
        }
        return awaitSecondary(key, future);
    }

    private static int[] awaitDepth(RegionKey key, Future<DepthEntry> future) {
        try {
            return future.get().values();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (DEPTH_CACHE.remove(key, future)) {
                DEPTH_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional surface depth cache interrupted", interrupted);
        } catch (ExecutionException failure) {
            if (DEPTH_CACHE.remove(key, future)) {
                DEPTH_COUNT.decrementAndGet();
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("regional surface depth cache failed", cause);
        }
    }

    private static double[] awaitSecondary(RegionKey key, Future<SecondaryEntry> future) {
        try {
            return future.get().values();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (SECONDARY_CACHE.remove(key, future)) {
                SECONDARY_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional secondary surface cache interrupted", interrupted);
        } catch (ExecutionException failure) {
            if (SECONDARY_CACHE.remove(key, future)) {
                SECONDARY_COUNT.decrementAndGet();
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("regional secondary surface cache failed", cause);
        }
    }

    private static int[] buildDepthValues(SurfaceSystem surfaceSystem, int regionX, int regionZ) {
        int[] values = new int[GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE * GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE];
        int baseBlockX = regionX << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
        int baseBlockZ = regionZ << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
        for (int localZ = 0; localZ < GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE; localZ++) {
            int rowBase = localZ << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
            int blockZ = baseBlockZ + localZ;
            for (int localX = 0; localX < GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE; localX++) {
                values[rowBase | localX] = surfaceSystem.getSurfaceDepth(baseBlockX + localX, blockZ);
            }
        }
        return values;
    }

    private static double[] buildSecondaryValues(SurfaceSystem surfaceSystem, int regionX, int regionZ) {
        double[] values = new double[GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE * GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE];
        int baseBlockX = regionX << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
        int baseBlockZ = regionZ << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
        for (int localZ = 0; localZ < GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE; localZ++) {
            int rowBase = localZ << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
            int blockZ = baseBlockZ + localZ;
            for (int localX = 0; localX < GARegionalSurfaceNoiseCache.REGION_BLOCK_SIZE; localX++) {
                values[rowBase | localX] = surfaceSystem.getSurfaceSecondary(baseBlockX + localX, blockZ);
            }
        }
        return values;
    }

    static void copyRegionInts(int[] source, int chunkMinX, int chunkMinZ, int[] out) {
        int localBaseX = chunkMinX & GARegionalSurfaceNoiseCache.REGION_BLOCK_MASK;
        int localBaseZ = chunkMinZ & GARegionalSurfaceNoiseCache.REGION_BLOCK_MASK;
        for (int localZ = 0; localZ < 16; localZ++) {
            System.arraycopy(
                    source,
                    ((localBaseZ + localZ) << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT) + localBaseX,
                    out,
                    localZ << 4,
                    16
            );
        }
    }

    static void copyRegionDoubles(double[] source, int chunkMinX, int chunkMinZ, double[] out) {
        int localBaseX = chunkMinX & GARegionalSurfaceNoiseCache.REGION_BLOCK_MASK;
        int localBaseZ = chunkMinZ & GARegionalSurfaceNoiseCache.REGION_BLOCK_MASK;
        for (int localZ = 0; localZ < 16; localZ++) {
            System.arraycopy(
                    source,
                    ((localBaseZ + localZ) << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT) + localBaseX,
                    out,
                    localZ << 4,
                    16
            );
        }
    }

    private static void evictDepthIfNeeded() {
        while (DEPTH_COUNT.get() > MAX_ENTRIES) {
            EvictionEntry<DepthEntry> oldest = DEPTH_ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (DEPTH_CACHE.remove(oldest.key(), oldest.future())) {
                DEPTH_COUNT.decrementAndGet();
            }
        }
    }

    private static void evictSecondaryIfNeeded() {
        while (SECONDARY_COUNT.get() > MAX_ENTRIES) {
            EvictionEntry<SecondaryEntry> oldest = SECONDARY_ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (SECONDARY_CACHE.remove(oldest.key(), oldest.future())) {
                SECONDARY_COUNT.decrementAndGet();
            }
        }
    }

    private record DepthEntry(int[] values) {
    }

    private record SecondaryEntry(double[] values) {
    }

    private record EvictionEntry<T>(RegionKey key, Future<T> future) {
    }

    private static final class RegionKey {
        private final SurfaceSystem surfaceSystem;
        private final int regionX;
        private final int regionZ;
        private final int hash;

        private RegionKey(SurfaceSystem surfaceSystem, int regionX, int regionZ) {
            this.surfaceSystem = surfaceSystem;
            this.regionX = regionX;
            this.regionZ = regionZ;

            int result = System.identityHashCode(surfaceSystem);
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
            return this.surfaceSystem == that.surfaceSystem
                    && this.regionX == that.regionX
                    && this.regionZ == that.regionZ;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
