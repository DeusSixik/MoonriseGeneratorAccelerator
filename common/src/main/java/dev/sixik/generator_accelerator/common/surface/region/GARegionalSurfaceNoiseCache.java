package dev.sixik.generator_accelerator.common.surface.region;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact shared cache for 4x4-chunk surface noise regions.
 *
 * <p>The cache stores immutable 64x64 block-column rasters keyed by
 * RandomState identity, noise key, and 4x4 chunk region. Values are exact
 * {@link NormalNoise#getValue(double, double, double)} samples at y=0.0 and do
 * not change worldgen semantics.</p>
 */
public final class GARegionalSurfaceNoiseCache {
    public static final int REGION_CHUNK_SHIFT = 2;
    public static final int REGION_CHUNK_SIZE = 1 << REGION_CHUNK_SHIFT;
    public static final int REGION_BLOCK_SHIFT = REGION_CHUNK_SHIFT + 4;
    public static final int REGION_BLOCK_SIZE = 1 << REGION_BLOCK_SHIFT;
    public static final int REGION_BLOCK_MASK = REGION_BLOCK_SIZE - 1;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.surface.regionalNoiseCache.enabled", "false")
    );
    private static final int MAX_ENTRIES = Math.max(
            16,
            Integer.getInteger("ga.surface.regionalNoiseCache.maxEntries", 256)
    );

    private static final ConcurrentHashMap<RegionKey, Future<RegionEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();

    private GARegionalSurfaceNoiseCache() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static double sample(
            RandomState randomState,
            ResourceKey<NormalNoise.NoiseParameters> noiseKey,
            int blockX,
            int blockZ
    ) {
        if (!ENABLED) {
            return randomState.getOrCreateNoise(noiseKey).getValue(blockX, 0.0, blockZ);
        }

        int regionX = blockX >> REGION_BLOCK_SHIFT;
        int regionZ = blockZ >> REGION_BLOCK_SHIFT;
        double[] values = regionValues(randomState, noiseKey, regionX, regionZ);
        return values[(blockX & REGION_BLOCK_MASK) | ((blockZ & REGION_BLOCK_MASK) << REGION_BLOCK_SHIFT)];
    }

    /**
     * Returns immutable region-backed values for a single 64x64 block-column
     * tile. Callers must not mutate the returned array.
     */
    public static double[] regionValues(
            RandomState randomState,
            ResourceKey<NormalNoise.NoiseParameters> noiseKey,
            int regionX,
            int regionZ
    ) {
        RegionKey key = new RegionKey(randomState, noiseKey, regionX, regionZ);
        Future<RegionEntry> future = CACHE.get(key);
        if (future == null) {
            FutureTask<RegionEntry> task = new FutureTask<>(() -> buildEntry(randomState, noiseKey, regionX, regionZ));
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

    static void clearForTests() {
        CACHE.clear();
        INSERTION_ORDER.clear();
        ENTRY_COUNT.set(0);
    }

    static int cacheSize() {
        return ENTRY_COUNT.get();
    }

    private static double[] awaitValues(RegionKey key, Future<RegionEntry> future) {
        try {
            return future.get().values();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional surface noise cache interrupted", interrupted);
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
            throw new IllegalStateException("regional surface noise cache failed", cause);
        }
    }

    private static RegionEntry buildEntry(
            RandomState randomState,
            ResourceKey<NormalNoise.NoiseParameters> noiseKey,
            int regionX,
            int regionZ
    ) {
        NormalNoise noise = randomState.getOrCreateNoise(noiseKey);
        double[] values = new double[REGION_BLOCK_SIZE * REGION_BLOCK_SIZE];
        int baseBlockX = regionX << REGION_BLOCK_SHIFT;
        int baseBlockZ = regionZ << REGION_BLOCK_SHIFT;

        for (int localZ = 0; localZ < REGION_BLOCK_SIZE; localZ++) {
            int rowBase = localZ << REGION_BLOCK_SHIFT;
            int blockZ = baseBlockZ + localZ;
            for (int localX = 0; localX < REGION_BLOCK_SIZE; localX++) {
                values[rowBase | localX] = noise.getValue(baseBlockX + localX, 0.0, blockZ);
            }
        }

        return new RegionEntry(values);
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

    private record RegionEntry(double[] values) {
    }

    private record EvictionEntry(RegionKey key, Future<RegionEntry> future) {
    }

    private static final class RegionKey {
        private final RandomState randomState;
        private final ResourceKey<NormalNoise.NoiseParameters> noiseKey;
        private final int regionX;
        private final int regionZ;
        private final int hash;

        private RegionKey(
                RandomState randomState,
                ResourceKey<NormalNoise.NoiseParameters> noiseKey,
                int regionX,
                int regionZ
        ) {
            this.randomState = randomState;
            this.noiseKey = noiseKey;
            this.regionX = regionX;
            this.regionZ = regionZ;

            int result = System.identityHashCode(randomState);
            result = 31 * result + noiseKey.hashCode();
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
            return this.randomState == that.randomState
                    && this.regionX == that.regionX
                    && this.regionZ == that.regionZ
                    && this.noiseKey.equals(that.noiseKey);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
