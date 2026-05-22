package dev.sixik.generator_accelerator.common.aquifer;

import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny, single-cell mirror of global fluid picker results for direct terrain
 * paths that inspect every block in the currently selected noise cell.
 */
public final class GAAquiferGlobalFluidCellCache {
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "ga.aquifer.globalFluidCellCache.enabled",
            "false"
    ));
    public static final int MAX_BLOCKS = Math.max(0, Integer.getInteger(
            "ga.aquifer.globalFluidCellCache.maxBlocks",
            512
    ));
    public static volatile boolean METRICS = Boolean.getBoolean("ga.aquifer.globalFluidCellCache.metrics");

    private static final int DEFAULT_CAPACITY = 128;
    private static final LongAdder FILLS = new LongAdder();
    private static final LongAdder PREPARE_REUSES = new LongAdder();
    private static final LongAdder PREPARE_MISSES = new LongAdder();
    private static final LongAdder READ_HITS = new LongAdder();
    private static final LongAdder READ_MISSES = new LongAdder();

    private byte[] kinds;
    private int[] blockIds;
    private int minBlockX;
    private int minBlockY;
    private int minBlockZ;
    private int cellWidth;
    private int cellHeight;
    private volatile boolean valid;

    public GAAquiferGlobalFluidCellCache() {
        int initialCapacity = Math.min(DEFAULT_CAPACITY, MAX_BLOCKS);
        this.kinds = new byte[initialCapacity];
        this.blockIds = new int[initialCapacity];
    }

    public boolean start(int minBlockX, int minBlockY, int minBlockZ, int cellWidth, int cellHeight) {
        this.valid = false;
        int count = blockCount(cellWidth, cellHeight);
        if (!ENABLED || count <= 0 || count > MAX_BLOCKS) {
            return false;
        }
        this.ensureCapacity(count);
        this.minBlockX = minBlockX;
        this.minBlockY = minBlockY;
        this.minBlockZ = minBlockZ;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        return true;
    }

    public void finish() {
        this.valid = true;
        if (METRICS) {
            FILLS.increment();
        }
    }

    public boolean matches(int minBlockX, int minBlockY, int minBlockZ, int cellWidth, int cellHeight) {
        boolean matches = this.valid
                && this.minBlockX == minBlockX
                && this.minBlockY == minBlockY
                && this.minBlockZ == minBlockZ
                && this.cellWidth == cellWidth
                && this.cellHeight == cellHeight;
        if (matches) {
            recordPrepareReuse();
        } else {
            recordPrepareMiss();
        }
        return matches;
    }

    public boolean contains(int x, int y, int z) {
        return this.valid
                && x >= this.minBlockX
                && x < this.minBlockX + this.cellWidth
                && y >= this.minBlockY
                && y < this.minBlockY + this.cellHeight
                && z >= this.minBlockZ
                && z < this.minBlockZ + this.cellWidth;
    }

    public void set(int index, byte kind, int blockId) {
        this.kinds[index] = kind;
        this.blockIds[index] = blockId;
    }

    public byte kindAt(int x, int y, int z) {
        if (!this.contains(x, y, z)) {
            recordReadMiss();
            return GAAquiferFluidGrid.KIND_UNKNOWN;
        }
        recordReadHit();
        return this.kinds[this.indexOf(x, y, z)];
    }

    public int blockIdAt(int x, int y, int z) {
        if (!this.contains(x, y, z)) {
            recordReadMiss();
            return GAAquiferPrimitiveAccess.GA_FALLBACK_RESULT;
        }
        recordReadHit();
        return this.blockIds[this.indexOf(x, y, z)];
    }

    public static long fills() {
        return FILLS.sum();
    }

    public static long prepareReuses() {
        return PREPARE_REUSES.sum();
    }

    public static long prepareMisses() {
        return PREPARE_MISSES.sum();
    }

    public static long readHits() {
        return READ_HITS.sum();
    }

    public static long readMisses() {
        return READ_MISSES.sum();
    }

    public static void setMetricsEnabled(boolean enabled) {
        METRICS = enabled;
    }

    public static void resetMetrics() {
        FILLS.reset();
        PREPARE_REUSES.reset();
        PREPARE_MISSES.reset();
        READ_HITS.reset();
        READ_MISSES.reset();
    }

    public static Snapshot snapshot() {
        long prepareReuses = prepareReuses();
        long prepareMisses = prepareMisses();
        long readHits = readHits();
        long readMisses = readMisses();
        return new Snapshot(
                ENABLED,
                METRICS,
                fills(),
                prepareReuses,
                prepareMisses,
                prepareReuses + prepareMisses == 0L ? 0.0D : (double) prepareReuses / (double) (prepareReuses + prepareMisses),
                readHits,
                readMisses,
                readHits + readMisses == 0L ? 0.0D : (double) readHits / (double) (readHits + readMisses),
                summary()
        );
    }

    public static String summary() {
        long prepareReuses = prepareReuses();
        long prepareMisses = prepareMisses();
        long readHits = readHits();
        long readMisses = readMisses();
        return "AquiferGlobalFluidCellCache enabled=" + ENABLED
                + ", metrics=" + METRICS
                + ", fills=" + fills()
                + ", prepareReuses=" + prepareReuses
                + ", prepareMisses=" + prepareMisses
                + ", prepareReuseRate=" + (prepareReuses + prepareMisses == 0L ? 0.0D : (double) prepareReuses / (double) (prepareReuses + prepareMisses))
                + ", readHits=" + readHits
                + ", readMisses=" + readMisses
                + ", readHitRate=" + (readHits + readMisses == 0L ? 0.0D : (double) readHits / (double) (readHits + readMisses));
    }

    private int indexOf(int x, int y, int z) {
        int localY = y - this.minBlockY;
        int localX = x - this.minBlockX;
        int localZ = z - this.minBlockZ;
        return (localY * this.cellWidth + localX) * this.cellWidth + localZ;
    }

    private void ensureCapacity(int count) {
        if (this.kinds.length >= count) {
            return;
        }
        this.kinds = new byte[count];
        this.blockIds = new int[count];
    }

    private static int blockCount(int cellWidth, int cellHeight) {
        if (cellWidth <= 0 || cellHeight <= 0) {
            return -1;
        }
        long count = (long) cellWidth * (long) cellWidth * (long) cellHeight;
        return count > Integer.MAX_VALUE ? -1 : (int) count;
    }

    private static void recordPrepareReuse() {
        if (METRICS) {
            PREPARE_REUSES.increment();
        }
    }

    private static void recordPrepareMiss() {
        if (METRICS) {
            PREPARE_MISSES.increment();
        }
    }

    private static void recordReadHit() {
        if (METRICS) {
            READ_HITS.increment();
        }
    }

    private static void recordReadMiss() {
        if (METRICS) {
            READ_MISSES.increment();
        }
    }

    public record Snapshot(
            boolean enabled,
            boolean metricsEnabled,
            long fills,
            long prepareReuses,
            long prepareMisses,
            double prepareReuseRate,
            long readHits,
            long readMisses,
            double readHitRate,
            String summary
    ) {
    }
}
