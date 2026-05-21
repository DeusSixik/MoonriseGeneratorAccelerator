package dev.sixik.generator_accelerator.common.noise.region;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exact shared 4x4-chunk cache for interpolator X-slices.
 *
 * <p>Each cached entry stores one immutable lattice slice at one world X
 * coordinate for one 4x4 chunk region. Neighboring chunks in the region share
 * boundary slice work, so we can reuse the exact interpolator lattice instead
 * of rebuilding it per chunk.</p>
 */
public final class GARegionalDensitySliceCache {
    public static final int REGION_CHUNK_SHIFT = 2;
    public static final int REGION_CHUNK_SIZE = 1 << REGION_CHUNK_SHIFT;
    public static final int REGION_BLOCK_SHIFT = REGION_CHUNK_SHIFT + 4;
    public static final int REGION_BLOCK_SIZE = 1 << REGION_BLOCK_SHIFT;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(
                    "ga.noise.regionalDensitySliceCache.enabled",
                    // Region-wide interpolator slices are exact in theory, but the current
                    // runtime path is too expensive and has shown seam instability in practice.
                    "false"
            )
    );
    private static final int MAX_ENTRIES = Math.max(
            16,
            Integer.getInteger("ga.noise.regionalDensitySliceCache.maxEntries", 96)
    );

    private static final ConcurrentHashMap<SliceKey, Future<SliceEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong CACHE_BUILDS = new AtomicLong();
    private static final AtomicLong CACHE_EVICTIONS = new AtomicLong();
    private static final AtomicLong WAIT_NANOS = new AtomicLong();

    private GARegionalDensitySliceCache() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static double[] sliceValues(
            GARegionalDensitySliceCacheOwner owner,
            int regionX,
            int regionZ,
            int localSliceX,
            Supplier<double[]> builder
    ) {
        if (!ENABLED || owner == null) {
            return builder.get();
        }

        SliceKey key = new SliceKey(owner, regionX, regionZ, localSliceX);
        Future<SliceEntry> future = CACHE.get(key);
        if (future == null) {
            CACHE_MISSES.incrementAndGet();
            FutureTask<SliceEntry> task = new FutureTask<>(() -> new SliceEntry(builder.get()));
            Future<SliceEntry> existing = CACHE.putIfAbsent(key, task);
            if (existing == null) {
                CACHE_BUILDS.incrementAndGet();
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
        return awaitValues(key, future);
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
        out.put("waitNanos", WAIT_NANOS.get());
        long approxBytes = 0L;
        for (Future<SliceEntry> future : CACHE.values()) {
            if (!future.isDone()) {
                continue;
            }
            try {
                SliceEntry entry = future.get();
                approxBytes += 32L + (long) entry.values().length * Double.BYTES;
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
        WAIT_NANOS.set(0L);
    }

    static int cacheSizeForTests() {
        return ENTRY_COUNT.get();
    }

    private static double[] awaitValues(SliceKey key, Future<SliceEntry> future) {
        long start = System.nanoTime();
        try {
            return future.get().values();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional density slice cache interrupted", interrupted);
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
            throw new IllegalStateException("regional density slice cache failed", cause);
        } finally {
            WAIT_NANOS.addAndGet(System.nanoTime() - start);
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

    private record SliceEntry(double[] values) {
    }

    private record EvictionEntry(SliceKey key, Future<SliceEntry> future) {
    }

    private static final class SliceKey {
        private final GARegionalDensitySliceCacheOwner owner;
        private final int regionX;
        private final int regionZ;
        private final int localSliceX;
        private final int hash;

        private SliceKey(GARegionalDensitySliceCacheOwner owner, int regionX, int regionZ, int localSliceX) {
            this.owner = owner;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.localSliceX = localSliceX;

            int result = owner.hashCode();
            result = 31 * result + regionX;
            result = 31 * result + regionZ;
            result = 31 * result + localSliceX;
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SliceKey that)) {
                return false;
            }
            return this.regionX == that.regionX
                    && this.regionZ == that.regionZ
                    && this.localSliceX == that.localSliceX
                    && this.owner.equals(that.owner);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
