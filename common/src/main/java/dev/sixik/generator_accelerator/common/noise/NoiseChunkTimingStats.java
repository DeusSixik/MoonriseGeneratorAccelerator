package dev.sixik.generator_accelerator.common.noise;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class NoiseChunkTimingStats {
    public static volatile boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "ga.noiseChunk.timingStats",
            Boolean.toString(GAConfigHolder.getConfig().dfc.noiseChunkTimingStats)));

    private static final LongAdder FILL_SLICE_CALLS = new LongAdder();
    private static final LongAdder FILL_SLICE_TOTAL_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_CALLS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_TOTAL_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_SETUP_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_CACHE_FILL_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_FAST_FILL_CALLS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_FAST_FILL_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_FALLBACK_FILL_CALLS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_FALLBACK_FILL_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_LAZY_RESOLVE_CALLS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_LAZY_RESOLVE_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_AP2_PRIMARY_CALLS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_AP2_PRIMARY_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_AP2_SECONDARY_CALLS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_AP2_SECONDARY_NANOS = new LongAdder();
    private static final LongAdder SELECT_CELL_YZ_AP2_ZERO_SECONDARY_SKIPS = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> SELECT_CELL_YZ_FAST_FILLER_CLASSES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SELECT_CELL_YZ_FAST_FILLER_DETAILS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SELECT_CELL_YZ_FALLBACK_FILLER_CLASSES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SELECT_CELL_YZ_FALLBACK_FILLER_DETAILS =
            new ConcurrentHashMap<>();

    private NoiseChunkTimingStats() {
    }

    public record Stats(
            boolean enabled,
            long fillSliceCalls,
            long fillSliceTotalNanos,
            long selectCellYzCalls,
            long selectCellYzTotalNanos,
            long selectCellYzSetupNanos,
            long selectCellYzCacheFillNanos,
            long selectCellYzFastFillCalls,
            long selectCellYzFastFillNanos,
            long selectCellYzFallbackFillCalls,
            long selectCellYzFallbackFillNanos,
            long selectCellYzLazyResolveCalls,
            long selectCellYzLazyResolveNanos,
            long selectCellYzAp2PrimaryCalls,
            long selectCellYzAp2PrimaryNanos,
            long selectCellYzAp2SecondaryCalls,
            long selectCellYzAp2SecondaryNanos,
            long selectCellYzAp2ZeroSecondarySkips,
            List<String> selectCellYzFastFillerClasses,
            List<String> selectCellYzFastFillerDetails,
            List<String> selectCellYzFallbackFillerClasses,
            List<String> selectCellYzFallbackFillerDetails
    ) {
    }

    public static Stats snapshotStats() {
        return new Stats(
                ENABLED,
                FILL_SLICE_CALLS.sum(),
                FILL_SLICE_TOTAL_NANOS.sum(),
                SELECT_CELL_YZ_CALLS.sum(),
                SELECT_CELL_YZ_TOTAL_NANOS.sum(),
                SELECT_CELL_YZ_SETUP_NANOS.sum(),
                SELECT_CELL_YZ_CACHE_FILL_NANOS.sum(),
                SELECT_CELL_YZ_FAST_FILL_CALLS.sum(),
                SELECT_CELL_YZ_FAST_FILL_NANOS.sum(),
                SELECT_CELL_YZ_FALLBACK_FILL_CALLS.sum(),
                SELECT_CELL_YZ_FALLBACK_FILL_NANOS.sum(),
                SELECT_CELL_YZ_LAZY_RESOLVE_CALLS.sum(),
                SELECT_CELL_YZ_LAZY_RESOLVE_NANOS.sum(),
                SELECT_CELL_YZ_AP2_PRIMARY_CALLS.sum(),
                SELECT_CELL_YZ_AP2_PRIMARY_NANOS.sum(),
                SELECT_CELL_YZ_AP2_SECONDARY_CALLS.sum(),
                SELECT_CELL_YZ_AP2_SECONDARY_NANOS.sum(),
                SELECT_CELL_YZ_AP2_ZERO_SECONDARY_SKIPS.sum(),
                snapshotFastFillerClasses(),
                snapshotFastFillerDetails(),
                snapshotFallbackFillerClasses(),
                snapshotFallbackFillerDetails()
        );
    }

    public static void reset() {
        FILL_SLICE_CALLS.reset();
        FILL_SLICE_TOTAL_NANOS.reset();
        SELECT_CELL_YZ_CALLS.reset();
        SELECT_CELL_YZ_TOTAL_NANOS.reset();
        SELECT_CELL_YZ_SETUP_NANOS.reset();
        SELECT_CELL_YZ_CACHE_FILL_NANOS.reset();
        SELECT_CELL_YZ_FAST_FILL_CALLS.reset();
        SELECT_CELL_YZ_FAST_FILL_NANOS.reset();
        SELECT_CELL_YZ_FALLBACK_FILL_CALLS.reset();
        SELECT_CELL_YZ_FALLBACK_FILL_NANOS.reset();
        SELECT_CELL_YZ_LAZY_RESOLVE_CALLS.reset();
        SELECT_CELL_YZ_LAZY_RESOLVE_NANOS.reset();
        SELECT_CELL_YZ_AP2_PRIMARY_CALLS.reset();
        SELECT_CELL_YZ_AP2_PRIMARY_NANOS.reset();
        SELECT_CELL_YZ_AP2_SECONDARY_CALLS.reset();
        SELECT_CELL_YZ_AP2_SECONDARY_NANOS.reset();
        SELECT_CELL_YZ_AP2_ZERO_SECONDARY_SKIPS.reset();
        SELECT_CELL_YZ_FAST_FILLER_CLASSES.clear();
        SELECT_CELL_YZ_FAST_FILLER_DETAILS.clear();
        SELECT_CELL_YZ_FALLBACK_FILLER_CLASSES.clear();
        SELECT_CELL_YZ_FALLBACK_FILLER_DETAILS.clear();
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static long startFillSlice() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void finishFillSlice(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        FILL_SLICE_CALLS.increment();
        FILL_SLICE_TOTAL_NANOS.add(Math.max(0L, System.nanoTime() - startNanos));
    }

    public static long startSelectCellYz() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static long startSelectCellYzCacheFill(long selectStartNanos) {
        if (selectStartNanos == 0L) {
            return 0L;
        }
        long now = System.nanoTime();
        SELECT_CELL_YZ_SETUP_NANOS.add(Math.max(0L, now - selectStartNanos));
        return now;
    }

    public static void finishSelectCellYz(long startNanos, long cacheFillStartNanos) {
        if (startNanos == 0L) {
            return;
        }
        long now = System.nanoTime();
        SELECT_CELL_YZ_CALLS.increment();
        SELECT_CELL_YZ_TOTAL_NANOS.add(Math.max(0L, now - startNanos));
        if (cacheFillStartNanos != 0L) {
            SELECT_CELL_YZ_CACHE_FILL_NANOS.add(Math.max(0L, now - cacheFillStartNanos));
        }
    }

    public static long startStage() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordFastFill(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        SELECT_CELL_YZ_FAST_FILL_CALLS.increment();
        SELECT_CELL_YZ_FAST_FILL_NANOS.add(Math.max(0L, System.nanoTime() - startNanos));
    }

    public static void recordFallbackFill(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        SELECT_CELL_YZ_FALLBACK_FILL_CALLS.increment();
        SELECT_CELL_YZ_FALLBACK_FILL_NANOS.add(Math.max(0L, System.nanoTime() - startNanos));
    }

    public static void recordLazyResolve(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        SELECT_CELL_YZ_LAZY_RESOLVE_CALLS.increment();
        SELECT_CELL_YZ_LAZY_RESOLVE_NANOS.add(Math.max(0L, System.nanoTime() - startNanos));
    }

    public static void recordAp2Primary(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        SELECT_CELL_YZ_AP2_PRIMARY_CALLS.increment();
        SELECT_CELL_YZ_AP2_PRIMARY_NANOS.add(Math.max(0L, System.nanoTime() - startNanos));
    }

    public static void recordAp2Secondary(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        SELECT_CELL_YZ_AP2_SECONDARY_CALLS.increment();
        SELECT_CELL_YZ_AP2_SECONDARY_NANOS.add(Math.max(0L, System.nanoTime() - startNanos));
    }

    public static void recordAp2ZeroSecondarySkip() {
        if (!ENABLED) {
            return;
        }
        SELECT_CELL_YZ_AP2_ZERO_SECONDARY_SKIPS.increment();
    }

    public static void recordFallbackFillerClass(Object filler) {
        if (!ENABLED || filler == null) {
            return;
        }
        increment(SELECT_CELL_YZ_FALLBACK_FILLER_CLASSES, filler.getClass().getName());
    }

    public static void recordFastFillerClass(Object filler) {
        if (!ENABLED || filler == null) {
            return;
        }
        increment(SELECT_CELL_YZ_FAST_FILLER_CLASSES, filler.getClass().getName());
    }

    public static void recordFastFillerDetail(String detail) {
        if (!ENABLED || detail == null || detail.isBlank()) {
            return;
        }
        increment(SELECT_CELL_YZ_FAST_FILLER_DETAILS, detail);
    }

    public static void recordFallbackFillerDetail(String detail) {
        if (!ENABLED || detail == null || detail.isBlank()) {
            return;
        }
        increment(SELECT_CELL_YZ_FALLBACK_FILLER_DETAILS, detail);
    }

    private static List<String> snapshotFallbackFillerClasses() {
        return snapshotCounts(SELECT_CELL_YZ_FALLBACK_FILLER_CLASSES);
    }

    private static List<String> snapshotFastFillerClasses() {
        return snapshotCounts(SELECT_CELL_YZ_FAST_FILLER_CLASSES);
    }

    private static List<String> snapshotFastFillerDetails() {
        return snapshotCounts(SELECT_CELL_YZ_FAST_FILLER_DETAILS);
    }

    private static List<String> snapshotFallbackFillerDetails() {
        return snapshotCounts(SELECT_CELL_YZ_FALLBACK_FILLER_DETAILS);
    }

    private static void increment(ConcurrentHashMap<String, LongAdder> counts, String key) {
        counts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    private static List<String> snapshotCounts(ConcurrentHashMap<String, LongAdder> counts) {
        List<ClassStats> raw = new ArrayList<>(counts.size());
        counts.forEach((name, counter) -> raw.add(new ClassStats(name, counter.sum())));
        raw.sort(Comparator.comparingLong(ClassStats::calls).reversed().thenComparing(ClassStats::className));
        List<String> out = new ArrayList<>(raw.size());
        for (ClassStats stat : raw) {
            out.add(stat.className() + "=" + stat.calls());
        }
        if (out.size() > 8) {
            return new ArrayList<>(out.subList(0, 8));
        }
        return out;
    }

    private record ClassStats(String className, long calls) {
    }
}
