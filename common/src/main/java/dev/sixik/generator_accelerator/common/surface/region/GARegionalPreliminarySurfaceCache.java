package dev.sixik.generator_accelerator.common.surface.region;

import dev.sixik.generator_accelerator.common.noise.CachedPointContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact quart-grid preliminary surface cache shared across 4x4 chunk regions.
 */
public final class GARegionalPreliminarySurfaceCache {
    public static final int QUART_SHIFT = 2;
    public static final int REGION_BLOCK_SHIFT = GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
    public static final int REGION_QUART_SHIFT = REGION_BLOCK_SHIFT - QUART_SHIFT;
    public static final int REGION_QUART_SIZE = 1 << REGION_QUART_SHIFT;
    public static final int REGION_QUART_MASK = REGION_QUART_SIZE - 1;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.surface.regionalPreliminaryCache.enabled", "false")
    );
    private static final int MAX_ENTRIES = Math.max(
            16,
            Integer.getInteger("ga.surface.regionalPreliminaryCache.maxEntries", 128)
    );

    private static final ConcurrentHashMap<RegionKey, Future<RegionEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();

    private GARegionalPreliminarySurfaceCache() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static int sample(
            DensityFunction density,
            NoiseSettings noiseSettings,
            int cellHeight,
            int blockX,
            int blockZ
    ) {
        int quartX = blockX >> QUART_SHIFT;
        int quartZ = blockZ >> QUART_SHIFT;
        if (!ENABLED) {
            return computeScalar(density, noiseSettings, cellHeight, quartX << QUART_SHIFT, quartZ << QUART_SHIFT);
        }

        int regionX = quartX >> REGION_QUART_SHIFT;
        int regionZ = quartZ >> REGION_QUART_SHIFT;
        int[] values = regionValues(density, noiseSettings, cellHeight, regionX, regionZ);
        return values[(quartX & REGION_QUART_MASK) | ((quartZ & REGION_QUART_MASK) << REGION_QUART_SHIFT)];
    }

    static void clearForTests() {
        CACHE.clear();
        INSERTION_ORDER.clear();
        ENTRY_COUNT.set(0);
    }

    static int cacheSize() {
        return ENTRY_COUNT.get();
    }

    private static int[] regionValues(
            DensityFunction density,
            NoiseSettings noiseSettings,
            int cellHeight,
            int regionX,
            int regionZ
    ) {
        RegionKey key = new RegionKey(density, noiseSettings, cellHeight, regionX, regionZ);
        Future<RegionEntry> future = CACHE.get(key);
        if (future == null) {
            FutureTask<RegionEntry> task = new FutureTask<>(() -> new RegionEntry(
                    buildValues(density, noiseSettings, cellHeight, regionX, regionZ)
            ));
            Future<RegionEntry> existing = CACHE.putIfAbsent(key, task);
            if (existing == null) {
                future = task;
                INSERTION_ORDER.offer(new EvictionEntry(key, task));
                ENTRY_COUNT.incrementAndGet();
                task.run();
                evictIfNeeded();
            } else {
                future = existing;
            }
        }
        return awaitValues(key, future);
    }

    private static int[] awaitValues(RegionKey key, Future<RegionEntry> future) {
        try {
            return future.get().values();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional preliminary surface cache interrupted", interrupted);
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
            throw new IllegalStateException("regional preliminary surface cache failed", cause);
        }
    }

    private static int[] buildValues(
            DensityFunction density,
            NoiseSettings noiseSettings,
            int cellHeight,
            int regionX,
            int regionZ
    ) {
        int[] values = new int[REGION_QUART_SIZE * REGION_QUART_SIZE];
        int baseQuartX = regionX << REGION_QUART_SHIFT;
        int baseQuartZ = regionZ << REGION_QUART_SHIFT;
        CachedPointContext context = new CachedPointContext();

        for (int localQuartZ = 0; localQuartZ < REGION_QUART_SIZE; localQuartZ++) {
            int rowBase = localQuartZ << REGION_QUART_SHIFT;
            int blockZ = (baseQuartZ + localQuartZ) << QUART_SHIFT;
            for (int localQuartX = 0; localQuartX < REGION_QUART_SIZE; localQuartX++) {
                int blockX = (baseQuartX + localQuartX) << QUART_SHIFT;
                values[rowBase | localQuartX] = compute(density, noiseSettings, cellHeight, blockX, blockZ, context);
            }
        }
        return values;
    }

    private static int computeScalar(
            DensityFunction density,
            NoiseSettings noiseSettings,
            int cellHeight,
            int blockX,
            int blockZ
    ) {
        return compute(density, noiseSettings, cellHeight, blockX, blockZ, new CachedPointContext());
    }

    private static int compute(
            DensityFunction density,
            NoiseSettings noiseSettings,
            int cellHeight,
            int blockX,
            int blockZ,
            CachedPointContext context
    ) {
        int minY = noiseSettings.minY();
        int maxY = minY + noiseSettings.height();
        for (int currentY = maxY; currentY >= minY; currentY -= cellHeight) {
            if (density.compute(context.update(blockX, currentY, blockZ)) > 0.390625D) {
                return currentY;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static void evictIfNeeded() {
        while (ENTRY_COUNT.get() > MAX_ENTRIES) {
            EvictionEntry oldest = INSERTION_ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (CACHE.remove(oldest.key(), oldest.future())) {
                ENTRY_COUNT.decrementAndGet();
            }
        }
    }

    private record RegionEntry(int[] values) {
    }

    private record EvictionEntry(RegionKey key, Future<RegionEntry> future) {
    }

    private static final class RegionKey {
        private final DensityFunction density;
        private final NoiseSettings noiseSettings;
        private final int cellHeight;
        private final int regionX;
        private final int regionZ;
        private final int hash;

        private RegionKey(
                DensityFunction density,
                NoiseSettings noiseSettings,
                int cellHeight,
                int regionX,
                int regionZ
        ) {
            this.density = density;
            this.noiseSettings = noiseSettings;
            this.cellHeight = cellHeight;
            this.regionX = regionX;
            this.regionZ = regionZ;

            int result = System.identityHashCode(density);
            result = 31 * result + System.identityHashCode(noiseSettings);
            result = 31 * result + cellHeight;
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
            return this.density == that.density
                    && this.noiseSettings == that.noiseSettings
                    && this.cellHeight == that.cellHeight
                    && this.regionX == that.regionX
                    && this.regionZ == that.regionZ;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
