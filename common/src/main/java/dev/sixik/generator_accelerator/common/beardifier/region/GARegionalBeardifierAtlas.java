package dev.sixik.generator_accelerator.common.beardifier.region;

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
 * Exact 4x4 region atlas for beardifier cell values.
 */
public final class GARegionalBeardifierAtlas {
    public static final int REGION_CHUNK_SHIFT = 2;
    public static final int REGION_BLOCK_SHIFT = REGION_CHUNK_SHIFT + 4;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.beardifier.regionalAtlas.enabled", "false")
    );
    private static final int MAX_REGIONS = Math.max(
            16,
            Integer.getInteger("ga.beardifier.regionalAtlas.maxRegions", 128)
    );

    private static final ConcurrentHashMap<RegionKey, Future<RegionEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger REGION_COUNT = new AtomicInteger();

    private static final AtomicLong REGION_HITS = new AtomicLong();
    private static final AtomicLong REGION_MISSES = new AtomicLong();
    private static final AtomicLong REGION_BUILDS = new AtomicLong();
    private static final AtomicLong REGION_EVICTIONS = new AtomicLong();
    private static final AtomicLong CELL_HITS = new AtomicLong();
    private static final AtomicLong CELL_MISSES = new AtomicLong();
    private static final AtomicLong CELL_BUILDS = new AtomicLong();

    private GARegionalBeardifierAtlas() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static View view(GARegionalBeardifierAtlasOwner owner, int blockX, int blockZ) {
        if (!ENABLED || owner == null) {
            return View.disabled(owner, blockX >> REGION_BLOCK_SHIFT, blockZ >> REGION_BLOCK_SHIFT);
        }
        int regionX = blockX >> REGION_BLOCK_SHIFT;
        int regionZ = blockZ >> REGION_BLOCK_SHIFT;
        return new View(owner, regionX, regionZ, regionEntry(owner, regionX, regionZ), true);
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
        out.put("cellHits", CELL_HITS.get());
        out.put("cellMisses", CELL_MISSES.get());
        out.put("cellBuilds", CELL_BUILDS.get());
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
        CELL_HITS.set(0L);
        CELL_MISSES.set(0L);
        CELL_BUILDS.set(0L);
    }

    private static RegionEntry regionEntry(GARegionalBeardifierAtlasOwner owner, int regionX, int regionZ) {
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
        return awaitRegion(key, future);
    }

    private static RegionEntry awaitRegion(RegionKey key, Future<RegionEntry> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                REGION_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional beardifier atlas interrupted", interrupted);
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
            throw new IllegalStateException("regional beardifier atlas failed", cause);
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

    public static final class View {
        private final GARegionalBeardifierAtlasOwner owner;
        private final int regionX;
        private final int regionZ;
        private final RegionEntry entry;
        private final boolean enabled;

        private View(
                GARegionalBeardifierAtlasOwner owner,
                int regionX,
                int regionZ,
                RegionEntry entry,
                boolean enabled
        ) {
            this.owner = owner;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.entry = entry;
            this.enabled = enabled;
        }

        private static View disabled(GARegionalBeardifierAtlasOwner owner, int regionX, int regionZ) {
            return new View(owner, regionX, regionZ, null, false);
        }

        public boolean enabled() {
            return this.enabled;
        }

        public double[] cellValues(
                int cellStartX,
                int cellStartY,
                int cellStartZ,
                Supplier<double[]> builder
        ) {
            if (!this.enabled || this.entry == null || !isRegionCoord(cellStartX, cellStartZ)) {
                return builder.get();
            }
            CellKey key = new CellKey(cellStartX, cellStartY, cellStartZ);
            Future<double[]> future = this.entry.cells.get(key);
            if (future != null) {
                CELL_HITS.incrementAndGet();
                return awaitCell(key, future, this.entry.cells);
            }
            CELL_MISSES.incrementAndGet();
            FutureTask<double[]> task = new FutureTask<>(() -> {
                CELL_BUILDS.incrementAndGet();
                return builder.get();
            });
            Future<double[]> existing = this.entry.cells.putIfAbsent(key, task);
            if (existing == null) {
                task.run();
                return awaitCell(key, task, this.entry.cells);
            }
            CELL_HITS.incrementAndGet();
            return awaitCell(key, existing, this.entry.cells);
        }

        private boolean isRegionCoord(int blockX, int blockZ) {
            return (blockX >> REGION_BLOCK_SHIFT) == this.regionX
                    && (blockZ >> REGION_BLOCK_SHIFT) == this.regionZ;
        }
    }

    private static double[] awaitCell(
            CellKey key,
            Future<double[]> future,
            ConcurrentHashMap<CellKey, Future<double[]>> cache
    ) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cache.remove(key, future);
            throw new IllegalStateException("regional beardifier cell interrupted", interrupted);
        } catch (ExecutionException failure) {
            cache.remove(key, future);
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("regional beardifier cell failed", cause);
        }
    }

    private static final class RegionEntry {
        private final ConcurrentHashMap<CellKey, Future<double[]>> cells = new ConcurrentHashMap<>();

        private long approximateHeapBytes() {
            long bytes = 0L;
            for (Future<double[]> future : this.cells.values()) {
                if (!future.isDone()) {
                    continue;
                }
                try {
                    double[] values = future.get();
                    bytes += 32L + (long) values.length * Double.BYTES;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ignored) {
                }
            }
            return bytes;
        }
    }

    private record EvictionEntry(RegionKey key, Future<RegionEntry> future) {
    }

    private static final class RegionKey {
        private final GARegionalBeardifierAtlasOwner owner;
        private final int regionX;
        private final int regionZ;
        private final int hash;

        private RegionKey(GARegionalBeardifierAtlasOwner owner, int regionX, int regionZ) {
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

    private static final class CellKey {
        private final int startX;
        private final int startY;
        private final int startZ;
        private final int hash;

        private CellKey(int startX, int startY, int startZ) {
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;

            int result = startX;
            result = 31 * result + startY;
            result = 31 * result + startZ;
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CellKey that)) {
                return false;
            }
            return this.startX == that.startX
                    && this.startY == that.startY
                    && this.startZ == that.startZ;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
