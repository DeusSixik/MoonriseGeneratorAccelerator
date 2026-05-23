package dev.sixik.generator_accelerator.common.flat_block_structure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default-off counters for raw 4096-int section storage experiments.
 */
public final class FlatBlockArrayMetrics {
    public static final boolean ENABLED =
            Boolean.getBoolean("ga.flatBlockArray.metrics") || Boolean.getBoolean("ga.wallTimeTelemetry");

    private static final AtomicLong RAW_DATA_HITS = new AtomicLong();
    private static final AtomicLong RAW_DATA_MISSES = new AtomicLong();
    private static final AtomicLong RAW_WRITE_HITS = new AtomicLong();
    private static final AtomicLong RAW_WRITE_MISSES = new AtomicLong();
    private static final AtomicLong RAW_WRITE_NOOPS = new AtomicLong();
    private static final AtomicLong UNPACK_CALLS = new AtomicLong();
    private static final AtomicLong UNPACK_SKIPS = new AtomicLong();
    private static final AtomicLong UNPACK_NANOS = new AtomicLong();
    private static final AtomicLong PACK_CALLS = new AtomicLong();
    private static final AtomicLong PACK_SKIPS = new AtomicLong();
    private static final AtomicLong PACK_STARTED_ONLY_AIR_CALLS = new AtomicLong();
    private static final AtomicLong PACK_STARTED_ONLY_AIR_DIRTY_CALLS = new AtomicLong();
    private static final AtomicLong PACK_STARTED_ONLY_AIR_FULL_SCAN_CALLS = new AtomicLong();
    private static final AtomicLong PACK_FULL_SECTION_CALLS = new AtomicLong();
    private static final AtomicLong PACK_NANOS = new AtomicLong();
    private static final AtomicLong FULL_SECTION_SCANS = new AtomicLong();
    private static final AtomicLong COPY_CALLS = new AtomicLong();
    private static final AtomicLong COPY_KNOWN_COUNTER_CALLS = new AtomicLong();
    private static final AtomicLong COPY_BYTES = new AtomicLong();
    private static final AtomicLong FILL_CALLS = new AtomicLong();
    private static final AtomicLong FILL_TOUCHED_BLOCKS = new AtomicLong();
    private static final AtomicLong FILL_CHANGED_BLOCKS = new AtomicLong();
    private static final AtomicLong RAW_POOL_HITS = new AtomicLong();
    private static final AtomicLong RAW_POOL_MISSES = new AtomicLong();
    private static final AtomicLong RAW_POOL_ALLOCATIONS = new AtomicLong();
    private static final AtomicLong RAW_POOL_RELEASES = new AtomicLong();
    private static final AtomicLong RAW_DIRTY_POOL_HITS = new AtomicLong();
    private static final AtomicLong RAW_DIRTY_POOL_MISSES = new AtomicLong();
    private static final AtomicLong RAW_DIRTY_POOL_ALLOCATIONS = new AtomicLong();
    private static final AtomicLong RAW_DIRTY_POOL_RELEASES = new AtomicLong();

    private FlatBlockArrayMetrics() {
    }

    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordRawData(boolean hit) {
        if (hit) {
            RAW_DATA_HITS.incrementAndGet();
        } else {
            RAW_DATA_MISSES.incrementAndGet();
        }
    }

    public static void recordRawWriteHit(boolean changed) {
        RAW_WRITE_HITS.incrementAndGet();
        if (!changed) {
            RAW_WRITE_NOOPS.incrementAndGet();
        }
    }

    public static void recordRawWriteMiss() {
        RAW_WRITE_MISSES.incrementAndGet();
    }

    public static void recordUnpack(long startNanos) {
        UNPACK_CALLS.incrementAndGet();
        UNPACK_NANOS.addAndGet(elapsed(startNanos));
    }

    public static void recordUnpackSkip() {
        UNPACK_SKIPS.incrementAndGet();
    }

    public static void recordPack(long startNanos, boolean startedOnlyAir) {
        PACK_CALLS.incrementAndGet();
        PACK_NANOS.addAndGet(elapsed(startNanos));
        if (startedOnlyAir) {
            PACK_STARTED_ONLY_AIR_CALLS.incrementAndGet();
        } else {
            PACK_FULL_SECTION_CALLS.incrementAndGet();
        }
    }

    public static void recordPackSkip() {
        PACK_SKIPS.incrementAndGet();
    }

    public static void recordStartedOnlyAirDirtyPack() {
        PACK_STARTED_ONLY_AIR_DIRTY_CALLS.incrementAndGet();
    }

    public static void recordStartedOnlyAirFullScanPack() {
        PACK_STARTED_ONLY_AIR_FULL_SCAN_CALLS.incrementAndGet();
        FULL_SECTION_SCANS.incrementAndGet();
    }

    public static void recordFullSectionScan() {
        FULL_SECTION_SCANS.incrementAndGet();
    }

    public static void recordCopy(boolean knownCounters) {
        COPY_CALLS.incrementAndGet();
        if (knownCounters) {
            COPY_KNOWN_COUNTER_CALLS.incrementAndGet();
        }
        COPY_BYTES.addAndGet(4096L * Integer.BYTES);
    }

    public static void recordFill(int touched, int changed) {
        FILL_CALLS.incrementAndGet();
        FILL_TOUCHED_BLOCKS.addAndGet(Math.max(0, touched));
        FILL_CHANGED_BLOCKS.addAndGet(Math.max(0, changed));
    }

    public static void recordRawPoolHit() {
        RAW_POOL_HITS.incrementAndGet();
    }

    public static void recordRawPoolMiss() {
        RAW_POOL_MISSES.incrementAndGet();
        RAW_POOL_ALLOCATIONS.incrementAndGet();
    }

    public static void recordRawPoolRelease() {
        RAW_POOL_RELEASES.incrementAndGet();
    }

    public static void recordRawDirtyPoolHit() {
        RAW_DIRTY_POOL_HITS.incrementAndGet();
    }

    public static void recordRawDirtyPoolMiss() {
        RAW_DIRTY_POOL_MISSES.incrementAndGet();
        RAW_DIRTY_POOL_ALLOCATIONS.incrementAndGet();
    }

    public static void recordRawDirtyPoolRelease() {
        RAW_DIRTY_POOL_RELEASES.incrementAndGet();
    }

    public static void reset() {
        RAW_DATA_HITS.set(0L);
        RAW_DATA_MISSES.set(0L);
        RAW_WRITE_HITS.set(0L);
        RAW_WRITE_MISSES.set(0L);
        RAW_WRITE_NOOPS.set(0L);
        UNPACK_CALLS.set(0L);
        UNPACK_SKIPS.set(0L);
        UNPACK_NANOS.set(0L);
        PACK_CALLS.set(0L);
        PACK_SKIPS.set(0L);
        PACK_STARTED_ONLY_AIR_CALLS.set(0L);
        PACK_STARTED_ONLY_AIR_DIRTY_CALLS.set(0L);
        PACK_STARTED_ONLY_AIR_FULL_SCAN_CALLS.set(0L);
        PACK_FULL_SECTION_CALLS.set(0L);
        PACK_NANOS.set(0L);
        FULL_SECTION_SCANS.set(0L);
        COPY_CALLS.set(0L);
        COPY_KNOWN_COUNTER_CALLS.set(0L);
        COPY_BYTES.set(0L);
        FILL_CALLS.set(0L);
        FILL_TOUCHED_BLOCKS.set(0L);
        FILL_CHANGED_BLOCKS.set(0L);
        RAW_POOL_HITS.set(0L);
        RAW_POOL_MISSES.set(0L);
        RAW_POOL_ALLOCATIONS.set(0L);
        RAW_POOL_RELEASES.set(0L);
        RAW_DIRTY_POOL_HITS.set(0L);
        RAW_DIRTY_POOL_MISSES.set(0L);
        RAW_DIRTY_POOL_ALLOCATIONS.set(0L);
        RAW_DIRTY_POOL_RELEASES.set(0L);
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("rawDataHits", RAW_DATA_HITS.get());
        out.put("rawDataMisses", RAW_DATA_MISSES.get());
        out.put("rawWriteHits", RAW_WRITE_HITS.get());
        out.put("rawWriteMisses", RAW_WRITE_MISSES.get());
        out.put("rawWriteNoops", RAW_WRITE_NOOPS.get());
        out.put("unpackCalls", UNPACK_CALLS.get());
        out.put("unpackSkips", UNPACK_SKIPS.get());
        out.put("unpackNanos", UNPACK_NANOS.get());
        out.put("packCalls", PACK_CALLS.get());
        out.put("packSkips", PACK_SKIPS.get());
        out.put("packStartedOnlyAirCalls", PACK_STARTED_ONLY_AIR_CALLS.get());
        out.put("packStartedOnlyAirDirtyCalls", PACK_STARTED_ONLY_AIR_DIRTY_CALLS.get());
        out.put("packStartedOnlyAirFullScanCalls", PACK_STARTED_ONLY_AIR_FULL_SCAN_CALLS.get());
        out.put("packFullSectionCalls", PACK_FULL_SECTION_CALLS.get());
        out.put("packNanos", PACK_NANOS.get());
        out.put("fullSectionScans", FULL_SECTION_SCANS.get());
        out.put("copyCalls", COPY_CALLS.get());
        out.put("copyKnownCounterCalls", COPY_KNOWN_COUNTER_CALLS.get());
        out.put("copyBytes", COPY_BYTES.get());
        out.put("fillCalls", FILL_CALLS.get());
        out.put("fillTouchedBlocks", FILL_TOUCHED_BLOCKS.get());
        out.put("fillChangedBlocks", FILL_CHANGED_BLOCKS.get());
        out.put("rawPoolHits", RAW_POOL_HITS.get());
        out.put("rawPoolMisses", RAW_POOL_MISSES.get());
        out.put("rawPoolAllocations", RAW_POOL_ALLOCATIONS.get());
        out.put("rawPoolReleases", RAW_POOL_RELEASES.get());
        out.put("rawDirtyPoolHits", RAW_DIRTY_POOL_HITS.get());
        out.put("rawDirtyPoolMisses", RAW_DIRTY_POOL_MISSES.get());
        out.put("rawDirtyPoolAllocations", RAW_DIRTY_POOL_ALLOCATIONS.get());
        out.put("rawDirtyPoolReleases", RAW_DIRTY_POOL_RELEASES.get());
        return out;
    }

    public static String summary() {
        if (!ENABLED) {
            return "GA flat raw metrics: disabled";
        }
        return "GA flat raw metrics: rawHits=" + RAW_DATA_HITS.get()
                + ", rawMisses=" + RAW_DATA_MISSES.get()
                + ", unpack=" + UNPACK_CALLS.get() + "/" + UNPACK_NANOS.get() / 1_000_000L + "ms"
                + ", pack=" + PACK_CALLS.get() + "/" + PACK_NANOS.get() / 1_000_000L + "ms"
                + ", copies=" + COPY_CALLS.get()
                + ", copyBytes=" + COPY_BYTES.get()
                + ", poolMisses=" + RAW_POOL_MISSES.get()
                + ", dirtyPoolMisses=" + RAW_DIRTY_POOL_MISSES.get();
    }

    private static long elapsed(long startNanos) {
        return startNanos == 0L ? 0L : Math.max(0L, System.nanoTime() - startNanos);
    }
}
