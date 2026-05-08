package dev.sixik.generator_accelerator.common.density.compiler.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional runtime counters for generated spline evaluation.
 *
 * <p>Instrumentation is emitted only when {@code -Ddfc.codegen.splineRuntimeStats=true}
 * is present during compilation/codegen, so the default hot path stays untouched.
 */
public final class DfcSplineStats {
    public static final boolean ENABLED = Boolean.getBoolean("dfc.codegen.splineRuntimeStats");

    public static final int SEARCH_LINEAR = 0;
    public static final int SEARCH_BINARY = 1;
    public static final int SEARCH_LUT = 2;

    public static final int EXIT_INTERIOR = 0;
    public static final int EXIT_LEFT_EXTRAPOLATION = 1;
    public static final int EXIT_RIGHT_EXTRAPOLATION = 2;

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder LINEAR_CALLS = new LongAdder();
    private static final LongAdder BINARY_CALLS = new LongAdder();
    private static final LongAdder INTERIOR_CALLS = new LongAdder();
    private static final LongAdder LEFT_EXTRAPOLATION_CALLS = new LongAdder();
    private static final LongAdder RIGHT_EXTRAPOLATION_CALLS = new LongAdder();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final LongAdder LINEAR_NANOS = new LongAdder();
    private static final LongAdder BINARY_NANOS = new LongAdder();
    private static final LongAdder LUT_CALLS = new LongAdder();
    private static final LongAdder LUT_NANOS = new LongAdder();

    private static final LongAdder BUCKET_LE_2_CALLS = new LongAdder();
    private static final LongAdder BUCKET_LE_2_NANOS = new LongAdder();
    private static final LongAdder BUCKET_3_TO_4_CALLS = new LongAdder();
    private static final LongAdder BUCKET_3_TO_4_NANOS = new LongAdder();
    private static final LongAdder BUCKET_5_TO_8_CALLS = new LongAdder();
    private static final LongAdder BUCKET_5_TO_8_NANOS = new LongAdder();
    private static final LongAdder BUCKET_GE_9_CALLS = new LongAdder();
    private static final LongAdder BUCKET_GE_9_NANOS = new LongAdder();
    private static final ConcurrentHashMap<String, ClassStatsCounter> CLASS_COUNTERS = new ConcurrentHashMap<>();

    private DfcSplineStats() {
    }

    public static void record(int pointCount, int searchMode, int exitKind, long nanos) {
        recordDetailed(null, pointCount, searchMode, exitKind, nanos);
    }

    public static void recordDetailed(String className, int pointCount, int searchMode, int exitKind, long nanos) {
        if (!ENABLED) {
            return;
        }
        long clampedNanos = Math.max(0L, nanos);
        CALLS.increment();
        TOTAL_NANOS.add(clampedNanos);

        if (searchMode == SEARCH_BINARY) {
            BINARY_CALLS.increment();
            BINARY_NANOS.add(clampedNanos);
        } else if (searchMode == SEARCH_LUT) {
            LUT_CALLS.increment();
            LUT_NANOS.add(clampedNanos);
        } else {
            LINEAR_CALLS.increment();
            LINEAR_NANOS.add(clampedNanos);
        }

        switch (exitKind) {
            case EXIT_LEFT_EXTRAPOLATION -> LEFT_EXTRAPOLATION_CALLS.increment();
            case EXIT_RIGHT_EXTRAPOLATION -> RIGHT_EXTRAPOLATION_CALLS.increment();
            default -> INTERIOR_CALLS.increment();
        }

        if (pointCount <= 2) {
            BUCKET_LE_2_CALLS.increment();
            BUCKET_LE_2_NANOS.add(clampedNanos);
        } else if (pointCount <= 4) {
            BUCKET_3_TO_4_CALLS.increment();
            BUCKET_3_TO_4_NANOS.add(clampedNanos);
        } else if (pointCount <= 8) {
            BUCKET_5_TO_8_CALLS.increment();
            BUCKET_5_TO_8_NANOS.add(clampedNanos);
        } else {
            BUCKET_GE_9_CALLS.increment();
            BUCKET_GE_9_NANOS.add(clampedNanos);
        }

        String normalizedClassName = normalizeClassName(className);
        if (!normalizedClassName.isEmpty()) {
            CLASS_COUNTERS.computeIfAbsent(normalizedClassName, ignored -> new ClassStatsCounter())
                    .record(pointCount, searchMode, exitKind, clampedNanos);
        }
    }

    public static Stats snapshot() {
        return new Stats(
                ENABLED,
                CALLS.sum(),
                LINEAR_CALLS.sum(),
                BINARY_CALLS.sum(),
                INTERIOR_CALLS.sum(),
                LEFT_EXTRAPOLATION_CALLS.sum(),
                RIGHT_EXTRAPOLATION_CALLS.sum(),
                TOTAL_NANOS.sum(),
                LINEAR_NANOS.sum(),
                BINARY_NANOS.sum(),
                LUT_CALLS.sum(),
                LUT_NANOS.sum(),
                new BucketStats(BUCKET_LE_2_CALLS.sum(), BUCKET_LE_2_NANOS.sum()),
                new BucketStats(BUCKET_3_TO_4_CALLS.sum(), BUCKET_3_TO_4_NANOS.sum()),
                new BucketStats(BUCKET_5_TO_8_CALLS.sum(), BUCKET_5_TO_8_NANOS.sum()),
                new BucketStats(BUCKET_GE_9_CALLS.sum(), BUCKET_GE_9_NANOS.sum()));
    }

    public static List<ClassStats> snapshotTopClasses(int limit) {
        List<ClassStats> out = new ArrayList<>(CLASS_COUNTERS.size());
        CLASS_COUNTERS.forEach((className, counter) -> {
            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(className);
            out.add(new ClassStats(
                    className,
                    entry != null ? entry.sourceRootClass() : "unknown",
                    entry != null ? entry.rootDebug() : "unknown",
                    counter.calls.sum(),
                    counter.linearCalls.sum(),
                    counter.binaryCalls.sum(),
                    counter.lutCalls.sum(),
                    counter.interiorCalls.sum(),
                    counter.leftExtrapolationCalls.sum(),
                    counter.rightExtrapolationCalls.sum(),
                    counter.totalNanos.sum(),
                    new BucketStats(counter.bucketLe2Calls.sum(), counter.bucketLe2Nanos.sum()),
                    new BucketStats(counter.bucket3To4Calls.sum(), counter.bucket3To4Nanos.sum()),
                    new BucketStats(counter.bucket5To8Calls.sum(), counter.bucket5To8Nanos.sum()),
                    new BucketStats(counter.bucketGe9Calls.sum(), counter.bucketGe9Nanos.sum())
            ));
        });
        out.sort(Comparator.comparingLong(ClassStats::totalNanos).reversed()
                .thenComparing(Comparator.comparingLong(ClassStats::calls).reversed())
                .thenComparing(ClassStats::className));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public static void reset() {
        CALLS.reset();
        LINEAR_CALLS.reset();
        BINARY_CALLS.reset();
        INTERIOR_CALLS.reset();
        LEFT_EXTRAPOLATION_CALLS.reset();
        RIGHT_EXTRAPOLATION_CALLS.reset();
        TOTAL_NANOS.reset();
        LINEAR_NANOS.reset();
        BINARY_NANOS.reset();
        LUT_CALLS.reset();
        LUT_NANOS.reset();
        BUCKET_LE_2_CALLS.reset();
        BUCKET_LE_2_NANOS.reset();
        BUCKET_3_TO_4_CALLS.reset();
        BUCKET_3_TO_4_NANOS.reset();
        BUCKET_5_TO_8_CALLS.reset();
        BUCKET_5_TO_8_NANOS.reset();
        BUCKET_GE_9_CALLS.reset();
        BUCKET_GE_9_NANOS.reset();
        CLASS_COUNTERS.clear();
    }

    public record BucketStats(long calls, long nanos) {
    }

    public record ClassStats(String className,
                             String sourceRootClass,
                             String rootDebug,
                             long calls,
                             long linearCalls,
                             long binaryCalls,
                             long lutCalls,
                             long interiorCalls,
                             long leftExtrapolationCalls,
                             long rightExtrapolationCalls,
                             long totalNanos,
                             BucketStats bucketLe2,
                             BucketStats bucket3To4,
                             BucketStats bucket5To8,
                             BucketStats bucketGe9) {
    }

    public record Stats(boolean enabled,
                        long calls,
                        long linearCalls,
                        long binaryCalls,
                        long interiorCalls,
                        long leftExtrapolationCalls,
                        long rightExtrapolationCalls,
                        long totalNanos,
                        long linearNanos,
                        long binaryNanos,
                        long lutCalls,
                        long lutNanos,
                        BucketStats bucketLe2,
                        BucketStats bucket3To4,
                        BucketStats bucket5To8,
                        BucketStats bucketGe9) {
    }

    private static String normalizeClassName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int hiddenSuffix = name.indexOf("/0x");
        String base = hiddenSuffix >= 0 ? name.substring(0, hiddenSuffix) : name;
        return base.replace('/', '.');
    }

    private static final class ClassStatsCounter {
        private final LongAdder calls = new LongAdder();
        private final LongAdder linearCalls = new LongAdder();
        private final LongAdder binaryCalls = new LongAdder();
        private final LongAdder lutCalls = new LongAdder();
        private final LongAdder interiorCalls = new LongAdder();
        private final LongAdder leftExtrapolationCalls = new LongAdder();
        private final LongAdder rightExtrapolationCalls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final LongAdder bucketLe2Calls = new LongAdder();
        private final LongAdder bucketLe2Nanos = new LongAdder();
        private final LongAdder bucket3To4Calls = new LongAdder();
        private final LongAdder bucket3To4Nanos = new LongAdder();
        private final LongAdder bucket5To8Calls = new LongAdder();
        private final LongAdder bucket5To8Nanos = new LongAdder();
        private final LongAdder bucketGe9Calls = new LongAdder();
        private final LongAdder bucketGe9Nanos = new LongAdder();

        private void record(int pointCount, int searchMode, int exitKind, long nanos) {
            calls.increment();
            totalNanos.add(nanos);
            if (searchMode == SEARCH_BINARY) {
                binaryCalls.increment();
            } else if (searchMode == SEARCH_LUT) {
                lutCalls.increment();
            } else {
                linearCalls.increment();
            }
            switch (exitKind) {
                case EXIT_LEFT_EXTRAPOLATION -> leftExtrapolationCalls.increment();
                case EXIT_RIGHT_EXTRAPOLATION -> rightExtrapolationCalls.increment();
                default -> interiorCalls.increment();
            }
            if (pointCount <= 2) {
                bucketLe2Calls.increment();
                bucketLe2Nanos.add(nanos);
            } else if (pointCount <= 4) {
                bucket3To4Calls.increment();
                bucket3To4Nanos.add(nanos);
            } else if (pointCount <= 8) {
                bucket5To8Calls.increment();
                bucket5To8Nanos.add(nanos);
            } else {
                bucketGe9Calls.increment();
                bucketGe9Nanos.add(nanos);
            }
        }
    }
}
