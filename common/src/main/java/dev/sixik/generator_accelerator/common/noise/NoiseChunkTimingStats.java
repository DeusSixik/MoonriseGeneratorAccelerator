package dev.sixik.generator_accelerator.common.noise;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class NoiseChunkTimingStats {
    public static volatile boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "ga.noiseChunk.timingStats",
            Boolean.toString(GAConfigHolder.getConfig().dfc.noiseChunkTimingStats)));
    public static volatile boolean STAGE_ENABLED = Boolean.parseBoolean(System.getProperty(
            "ga.noiseChunk.stageTimingStats",
            Boolean.toString(GAConfigHolder.getConfig().dfc.noiseChunkStageTimingStats)));

    private static final LongAdder FILL_SLICE_CALLS = new LongAdder();
    private static final LongAdder FILL_SLICE_TOTAL_NANOS = new LongAdder();
    private static final LongAdder FILL_SLICE_BATCH_SURFACE_POINTS = new LongAdder();
    private static final LongAdder FILL_SLICE_BATCH_SURFACE_COLUMNS = new LongAdder();
    private static final LongAdder FILL_SLICE_BATCH_SURFACE_Y = new LongAdder();
    private static final LongAdder FILL_SLICE_BATCH_SURFACE_INTERPOLATORS = new LongAdder();
    private static final AtomicLong FILL_SLICE_BATCH_SURFACE_MAX_POINTS = new AtomicLong();
    private static final LongAdder FILL_SLICE_PAYLOAD_ROOTS = new LongAdder();
    private static final LongAdder FILL_SLICE_PAYLOAD_READY_ROOTS = new LongAdder();
    private static final LongAdder FILL_SLICE_PAYLOAD_EXTERN_ROOTS = new LongAdder();
    private static final LongAdder FILL_SLICE_PAYLOAD_POINTS = new LongAdder();
    private static final LongAdder FILL_SLICE_PAYLOAD_READY_POINTS = new LongAdder();
    private static final LongAdder FILL_SLICE_PAYLOAD_EXTERN_POINTS = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_PAYLOAD_EXTERN_CLASSES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_PAYLOAD_EXTERN_LEAF_CLASSES =
            new ConcurrentHashMap<>();
    private static final LongAdder FILL_SLICE_EXTERN_INPUT_NANOS = new LongAdder();
    private static final LongAdder FILL_SLICE_EXTERN_INPUT_DIRECT_ATTEMPTS = new LongAdder();
    private static final LongAdder FILL_SLICE_EXTERN_INPUT_DIRECT_HITS = new LongAdder();
    private static final LongAdder FILL_SLICE_EXTERN_INPUT_DIRECT_MISSES = new LongAdder();
    private static final LongAdder FILL_SLICE_EXTERN_INPUT_COMPUTES = new LongAdder();
    private static final LongAdder FILL_SLICE_EXTERN_INPUT_WRAPPED_COMPUTES = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_CLASSES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_CLASSES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_EXTERN_INPUT_COMPUTE_CLASSES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_DETAILS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_DETAILS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_EXTERN_INPUT_COMPUTE_DETAILS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_PAYLOAD_MISSING_CLASSES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_PAYLOAD_BLOCKED_REASONS =
            new ConcurrentHashMap<>();
    private static final LongAdder FILL_SLICE_LAZY_COMPILE_ATTEMPTS = new LongAdder();
    private static final LongAdder FILL_SLICE_LAZY_COMPILE_SUCCESSES = new LongAdder();
    private static final LongAdder FILL_SLICE_LAZY_COMPILE_FAILURES = new LongAdder();
    private static final LongAdder FILL_SLICE_LAZY_COMPILE_BUDGET_SKIPS = new LongAdder();
    private static final LongAdder FILL_SLICE_GPU_COLLECTION_ATTEMPTS = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> FILL_SLICE_GPU_COLLECTION_SKIPS =
            new ConcurrentHashMap<>();
    private static final LongAdder FILL_SLICE_GPU_CANDIDATE_ROOTS = new LongAdder();
    private static final AtomicLong FILL_SLICE_GPU_BEST_GROUP_MAX_ROOTS = new AtomicLong();
    private static final AtomicLong FILL_SLICE_GPU_BEST_GROUP_MAX_POINTS = new AtomicLong();
    private static final LongAdder FILL_SLICE_GPU_GROUPED_LAUNCHES = new LongAdder();
    private static final LongAdder FILL_SLICE_GPU_GROUPED_ROOTS = new LongAdder();
    private static final LongAdder FILL_SLICE_GPU_GROUPED_POINTS = new LongAdder();
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
            boolean stageTimingEnabled,
            long fillSliceCalls,
            long fillSliceTotalNanos,
            long fillSliceBatchSurfacePoints,
            long fillSliceBatchSurfaceMaxPoints,
            long fillSliceBatchSurfaceColumns,
            long fillSliceBatchSurfaceY,
            long fillSliceBatchSurfaceInterpolators,
            long fillSlicePayloadRoots,
            long fillSlicePayloadReadyRoots,
            long fillSlicePayloadExternRoots,
            long fillSlicePayloadPoints,
            long fillSlicePayloadReadyPoints,
            long fillSlicePayloadExternPoints,
            List<String> fillSlicePayloadExternClasses,
            List<String> fillSlicePayloadExternLeafClasses,
            long fillSliceExternInputNanos,
            long fillSliceExternInputDirectAttempts,
            long fillSliceExternInputDirectHits,
            long fillSliceExternInputDirectMisses,
            long fillSliceExternInputComputes,
            long fillSliceExternInputWrappedComputes,
            List<String> fillSliceExternInputDirectHitClasses,
            List<String> fillSliceExternInputComputeClasses,
            List<String> fillSliceExternInputDirectMissClasses,
            List<String> fillSliceExternInputDirectHitDetails,
            List<String> fillSliceExternInputDirectMissDetails,
            List<String> fillSliceExternInputComputeDetails,
            List<String> fillSlicePayloadMissingClasses,
            List<String> fillSlicePayloadBlockedReasons,
            long fillSliceLazyCompileAttempts,
            long fillSliceLazyCompileSuccesses,
            long fillSliceLazyCompileFailures,
            long fillSliceLazyCompileBudgetSkips,
            long fillSliceGpuCollectionAttempts,
            List<String> fillSliceGpuCollectionSkips,
            long fillSliceGpuCandidateRoots,
            long fillSliceGpuBestGroupMaxRoots,
            long fillSliceGpuBestGroupMaxPoints,
            long fillSliceGpuGroupedLaunches,
            long fillSliceGpuGroupedRoots,
            long fillSliceGpuGroupedPoints,
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
                stageTimingEnabled(),
                FILL_SLICE_CALLS.sum(),
                FILL_SLICE_TOTAL_NANOS.sum(),
                FILL_SLICE_BATCH_SURFACE_POINTS.sum(),
                FILL_SLICE_BATCH_SURFACE_MAX_POINTS.get(),
                FILL_SLICE_BATCH_SURFACE_COLUMNS.sum(),
                FILL_SLICE_BATCH_SURFACE_Y.sum(),
                FILL_SLICE_BATCH_SURFACE_INTERPOLATORS.sum(),
                FILL_SLICE_PAYLOAD_ROOTS.sum(),
                FILL_SLICE_PAYLOAD_READY_ROOTS.sum(),
                FILL_SLICE_PAYLOAD_EXTERN_ROOTS.sum(),
                FILL_SLICE_PAYLOAD_POINTS.sum(),
                FILL_SLICE_PAYLOAD_READY_POINTS.sum(),
                FILL_SLICE_PAYLOAD_EXTERN_POINTS.sum(),
                snapshotFillSlicePayloadExternClasses(),
                snapshotFillSlicePayloadExternLeafClasses(),
                FILL_SLICE_EXTERN_INPUT_NANOS.sum(),
                FILL_SLICE_EXTERN_INPUT_DIRECT_ATTEMPTS.sum(),
                FILL_SLICE_EXTERN_INPUT_DIRECT_HITS.sum(),
                FILL_SLICE_EXTERN_INPUT_DIRECT_MISSES.sum(),
                FILL_SLICE_EXTERN_INPUT_COMPUTES.sum(),
                FILL_SLICE_EXTERN_INPUT_WRAPPED_COMPUTES.sum(),
                snapshotFillSliceExternInputDirectHitClasses(),
                snapshotFillSliceExternInputComputeClasses(),
                snapshotFillSliceExternInputDirectMissClasses(),
                snapshotFillSliceExternInputDirectHitDetails(),
                snapshotFillSliceExternInputDirectMissDetails(),
                snapshotFillSliceExternInputComputeDetails(),
                snapshotFillSlicePayloadMissingClasses(),
                snapshotFillSlicePayloadBlockedReasons(),
                FILL_SLICE_LAZY_COMPILE_ATTEMPTS.sum(),
                FILL_SLICE_LAZY_COMPILE_SUCCESSES.sum(),
                FILL_SLICE_LAZY_COMPILE_FAILURES.sum(),
                FILL_SLICE_LAZY_COMPILE_BUDGET_SKIPS.sum(),
                FILL_SLICE_GPU_COLLECTION_ATTEMPTS.sum(),
                snapshotFillSliceGpuCollectionSkips(),
                FILL_SLICE_GPU_CANDIDATE_ROOTS.sum(),
                FILL_SLICE_GPU_BEST_GROUP_MAX_ROOTS.get(),
                FILL_SLICE_GPU_BEST_GROUP_MAX_POINTS.get(),
                FILL_SLICE_GPU_GROUPED_LAUNCHES.sum(),
                FILL_SLICE_GPU_GROUPED_ROOTS.sum(),
                FILL_SLICE_GPU_GROUPED_POINTS.sum(),
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
        FILL_SLICE_BATCH_SURFACE_POINTS.reset();
        FILL_SLICE_BATCH_SURFACE_COLUMNS.reset();
        FILL_SLICE_BATCH_SURFACE_Y.reset();
        FILL_SLICE_BATCH_SURFACE_INTERPOLATORS.reset();
        FILL_SLICE_BATCH_SURFACE_MAX_POINTS.set(0L);
        FILL_SLICE_PAYLOAD_ROOTS.reset();
        FILL_SLICE_PAYLOAD_READY_ROOTS.reset();
        FILL_SLICE_PAYLOAD_EXTERN_ROOTS.reset();
        FILL_SLICE_PAYLOAD_POINTS.reset();
        FILL_SLICE_PAYLOAD_READY_POINTS.reset();
        FILL_SLICE_PAYLOAD_EXTERN_POINTS.reset();
        FILL_SLICE_EXTERN_INPUT_NANOS.reset();
        FILL_SLICE_EXTERN_INPUT_DIRECT_ATTEMPTS.reset();
        FILL_SLICE_EXTERN_INPUT_DIRECT_HITS.reset();
        FILL_SLICE_EXTERN_INPUT_DIRECT_MISSES.reset();
        FILL_SLICE_EXTERN_INPUT_COMPUTES.reset();
        FILL_SLICE_EXTERN_INPUT_WRAPPED_COMPUTES.reset();
        FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_CLASSES.clear();
        FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_CLASSES.clear();
        FILL_SLICE_EXTERN_INPUT_COMPUTE_CLASSES.clear();
        FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_DETAILS.clear();
        FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_DETAILS.clear();
        FILL_SLICE_EXTERN_INPUT_COMPUTE_DETAILS.clear();
        FILL_SLICE_PAYLOAD_MISSING_CLASSES.clear();
        FILL_SLICE_PAYLOAD_BLOCKED_REASONS.clear();
        FILL_SLICE_LAZY_COMPILE_ATTEMPTS.reset();
        FILL_SLICE_LAZY_COMPILE_SUCCESSES.reset();
        FILL_SLICE_LAZY_COMPILE_FAILURES.reset();
        FILL_SLICE_LAZY_COMPILE_BUDGET_SKIPS.reset();
        FILL_SLICE_GPU_COLLECTION_ATTEMPTS.reset();
        FILL_SLICE_GPU_COLLECTION_SKIPS.clear();
        FILL_SLICE_GPU_CANDIDATE_ROOTS.reset();
        FILL_SLICE_GPU_BEST_GROUP_MAX_ROOTS.set(0L);
        FILL_SLICE_GPU_BEST_GROUP_MAX_POINTS.set(0L);
        FILL_SLICE_GPU_GROUPED_LAUNCHES.reset();
        FILL_SLICE_GPU_GROUPED_ROOTS.reset();
        FILL_SLICE_GPU_GROUPED_POINTS.reset();
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

    public static void setStageEnabled(boolean enabled) {
        STAGE_ENABLED = enabled;
    }

    public static boolean stageTimingEnabled() {
        return STAGE_ENABLED && ENABLED;
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

    public static void recordFillSliceBatchSurface(int columns, int yCount, int interpolators) {
        if (!ENABLED || columns <= 0 || yCount <= 0 || interpolators <= 0) {
            return;
        }
        long points;
        try {
            points = Math.multiplyExact(Math.multiplyExact((long) columns, yCount), interpolators);
        } catch (ArithmeticException ignored) {
            points = Long.MAX_VALUE;
        }
        FILL_SLICE_BATCH_SURFACE_POINTS.add(points);
        FILL_SLICE_BATCH_SURFACE_COLUMNS.add(columns);
        FILL_SLICE_BATCH_SURFACE_Y.add(yCount);
        FILL_SLICE_BATCH_SURFACE_INTERPOLATORS.add(interpolators);
        updateMax(FILL_SLICE_BATCH_SURFACE_MAX_POINTS, points);
    }

    public static void recordFillSlicePayloadRoot(
            boolean payloadReady,
            boolean hasExternInputs,
            long points,
            Object root,
            String blockedReason) {
        if (!ENABLED || points <= 0L) {
            return;
        }
        FILL_SLICE_PAYLOAD_ROOTS.increment();
        FILL_SLICE_PAYLOAD_POINTS.add(points);
        if (payloadReady) {
            FILL_SLICE_PAYLOAD_READY_ROOTS.increment();
            FILL_SLICE_PAYLOAD_READY_POINTS.add(points);
            if (hasExternInputs) {
                FILL_SLICE_PAYLOAD_EXTERN_ROOTS.increment();
                FILL_SLICE_PAYLOAD_EXTERN_POINTS.add(points);
                increment(FILL_SLICE_PAYLOAD_EXTERN_CLASSES,
                        root == null ? "null" : root.getClass().getName());
            }
        } else {
            increment(FILL_SLICE_PAYLOAD_MISSING_CLASSES,
                    root == null ? "null" : root.getClass().getName());
            if (blockedReason != null && !blockedReason.isBlank()) {
                increment(FILL_SLICE_PAYLOAD_BLOCKED_REASONS, blockedReason);
            }
        }
    }

    public static void recordFillSlicePayloadExternLeaf(String leafClass) {
        if (!ENABLED || leafClass == null || leafClass.isBlank()) {
            return;
        }
        increment(FILL_SLICE_PAYLOAD_EXTERN_LEAF_CLASSES, leafClass);
    }

    public static void recordFillSliceExternInputNanos(long nanos) {
        if (ENABLED && nanos > 0L) {
            FILL_SLICE_EXTERN_INPUT_NANOS.add(nanos);
        }
    }

    public static void recordFillSliceExternInputStats(
            long directAttempts,
            long directHits,
            long directMisses,
            long computes,
            long wrappedComputes) {
        if (!ENABLED) {
            return;
        }
        if (directAttempts > 0L) {
            FILL_SLICE_EXTERN_INPUT_DIRECT_ATTEMPTS.add(directAttempts);
        }
        if (directHits > 0L) {
            FILL_SLICE_EXTERN_INPUT_DIRECT_HITS.add(directHits);
        }
        if (directMisses > 0L) {
            FILL_SLICE_EXTERN_INPUT_DIRECT_MISSES.add(directMisses);
        }
        if (computes > 0L) {
            FILL_SLICE_EXTERN_INPUT_COMPUTES.add(computes);
        }
        if (wrappedComputes > 0L) {
            FILL_SLICE_EXTERN_INPUT_WRAPPED_COMPUTES.add(wrappedComputes);
        }
    }

    public static void recordFillSliceExternInputClassStats(
            String className,
            String detail,
            long directHits,
            long directMisses,
            long computes) {
        if (!ENABLED || className == null || className.isBlank()) {
            return;
        }
        if (directHits > 0L) {
            FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_CLASSES
                    .computeIfAbsent(className, ignored -> new LongAdder())
                    .add(directHits);
            if (detail != null && !detail.isBlank()) {
                FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_DETAILS
                        .computeIfAbsent(detail, ignored -> new LongAdder())
                        .add(directHits);
            }
        }
        if (directMisses > 0L) {
            FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_CLASSES
                    .computeIfAbsent(className, ignored -> new LongAdder())
                    .add(directMisses);
            if (detail != null && !detail.isBlank()) {
                FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_DETAILS
                        .computeIfAbsent(detail, ignored -> new LongAdder())
                        .add(directMisses);
            }
        }
        if (computes > 0L) {
            FILL_SLICE_EXTERN_INPUT_COMPUTE_CLASSES
                    .computeIfAbsent(className, ignored -> new LongAdder())
                    .add(computes);
            if (detail != null && !detail.isBlank()) {
                FILL_SLICE_EXTERN_INPUT_COMPUTE_DETAILS
                        .computeIfAbsent(detail, ignored -> new LongAdder())
                        .add(computes);
            }
        }
    }

    public static void recordFillSliceLazyCompileAttempt() {
        if (ENABLED) {
            FILL_SLICE_LAZY_COMPILE_ATTEMPTS.increment();
        }
    }

    public static void recordFillSliceLazyCompileSuccess() {
        if (ENABLED) {
            FILL_SLICE_LAZY_COMPILE_SUCCESSES.increment();
        }
    }

    public static void recordFillSliceLazyCompileFailure() {
        if (ENABLED) {
            FILL_SLICE_LAZY_COMPILE_FAILURES.increment();
        }
    }

    public static void recordFillSliceLazyCompileBudgetSkip() {
        if (ENABLED) {
            FILL_SLICE_LAZY_COMPILE_BUDGET_SKIPS.increment();
        }
    }

    public static void recordFillSliceGpuCollectionAttempt() {
        if (ENABLED) {
            FILL_SLICE_GPU_COLLECTION_ATTEMPTS.increment();
        }
    }

    public static void recordFillSliceGpuCollectionSkip(String reason) {
        if (!ENABLED || reason == null || reason.isBlank()) {
            return;
        }
        increment(FILL_SLICE_GPU_COLLECTION_SKIPS, reason);
    }

    public static void recordFillSliceGpuGroupCandidate(int candidateRoots, int bestGroupRoots, long bestGroupPoints) {
        if (!ENABLED) {
            return;
        }
        if (candidateRoots > 0) {
            FILL_SLICE_GPU_CANDIDATE_ROOTS.add(candidateRoots);
        }
        if (bestGroupRoots > 0) {
            updateMax(FILL_SLICE_GPU_BEST_GROUP_MAX_ROOTS, bestGroupRoots);
        }
        if (bestGroupPoints > 0L) {
            updateMax(FILL_SLICE_GPU_BEST_GROUP_MAX_POINTS, bestGroupPoints);
        }
    }

    public static void recordFillSliceGpuGroupLaunch(int roots, long points) {
        if (!ENABLED) {
            return;
        }
        FILL_SLICE_GPU_GROUPED_LAUNCHES.increment();
        if (roots > 0) {
            FILL_SLICE_GPU_GROUPED_ROOTS.add(roots);
        }
        if (points > 0L) {
            FILL_SLICE_GPU_GROUPED_POINTS.add(points);
        }
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
        return stageTimingEnabled() ? System.nanoTime() : 0L;
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

    public static void recordFillPathCounts(int fastFills, int fallbackFills) {
        if (!ENABLED) {
            return;
        }
        if (fastFills > 0) {
            SELECT_CELL_YZ_FAST_FILL_CALLS.add(fastFills);
        }
        if (fallbackFills > 0) {
            SELECT_CELL_YZ_FALLBACK_FILL_CALLS.add(fallbackFills);
        }
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

    public static void recordAp2PrimaryCall() {
        if (ENABLED) {
            SELECT_CELL_YZ_AP2_PRIMARY_CALLS.increment();
        }
    }

    public static void recordAp2Secondary(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        SELECT_CELL_YZ_AP2_SECONDARY_CALLS.increment();
        SELECT_CELL_YZ_AP2_SECONDARY_NANOS.add(Math.max(0L, System.nanoTime() - startNanos));
    }

    public static void recordAp2SecondaryCall() {
        if (ENABLED) {
            SELECT_CELL_YZ_AP2_SECONDARY_CALLS.increment();
        }
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

    private static List<String> snapshotFillSlicePayloadMissingClasses() {
        return snapshotCounts(FILL_SLICE_PAYLOAD_MISSING_CLASSES);
    }

    private static List<String> snapshotFillSlicePayloadExternClasses() {
        return snapshotCounts(FILL_SLICE_PAYLOAD_EXTERN_CLASSES);
    }

    private static List<String> snapshotFillSlicePayloadExternLeafClasses() {
        return snapshotCounts(FILL_SLICE_PAYLOAD_EXTERN_LEAF_CLASSES);
    }

    private static List<String> snapshotFillSliceExternInputDirectHitClasses() {
        return snapshotCounts(FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_CLASSES);
    }

    private static List<String> snapshotFillSliceExternInputDirectMissClasses() {
        return snapshotCounts(FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_CLASSES);
    }

    private static List<String> snapshotFillSliceExternInputComputeClasses() {
        return snapshotCounts(FILL_SLICE_EXTERN_INPUT_COMPUTE_CLASSES);
    }

    private static List<String> snapshotFillSliceExternInputDirectHitDetails() {
        return snapshotCounts(FILL_SLICE_EXTERN_INPUT_DIRECT_HIT_DETAILS);
    }

    private static List<String> snapshotFillSliceExternInputDirectMissDetails() {
        return snapshotCounts(FILL_SLICE_EXTERN_INPUT_DIRECT_MISS_DETAILS);
    }

    private static List<String> snapshotFillSliceExternInputComputeDetails() {
        return snapshotCounts(FILL_SLICE_EXTERN_INPUT_COMPUTE_DETAILS);
    }

    private static List<String> snapshotFillSlicePayloadBlockedReasons() {
        return snapshotCounts(FILL_SLICE_PAYLOAD_BLOCKED_REASONS);
    }

    private static List<String> snapshotFillSliceGpuCollectionSkips() {
        return snapshotCounts(FILL_SLICE_GPU_COLLECTION_SKIPS);
    }

    private static void increment(ConcurrentHashMap<String, LongAdder> counts, String key) {
        counts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) {
                return;
            }
        } while (!target.compareAndSet(current, value));
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
