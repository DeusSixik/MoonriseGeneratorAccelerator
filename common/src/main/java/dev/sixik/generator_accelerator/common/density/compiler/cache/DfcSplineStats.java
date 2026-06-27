package dev.sixik.generator_accelerator.common.density.compiler.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional runtime counters for generated spline evaluation.
 *
 * <p>Generated hooks are runtime-gated so `/ga diagnostics start` can enable
 * counters after startup without JVM arguments.
 */
public final class DfcSplineStats {
    public static volatile boolean ENABLED = Boolean.getBoolean("dfc.codegen.splineRuntimeStats");
    private static final int MAX_TRACKED_CLASSES = Integer.getInteger("ga.dfc.splineStats.maxTrackedClasses", 256);

    public static final int SEARCH_LINEAR = 0;
    public static final int SEARCH_BINARY = 1;
    public static final int SEARCH_LUT = 2;

    public static final int EXIT_INTERIOR = 0;
    public static final int EXIT_LEFT_EXTRAPOLATION = 1;
    public static final int EXIT_RIGHT_EXTRAPOLATION = 2;

    private static final int SAMPLE_SHIFT = sampleShift();
    private static final int SAMPLE_RATE = 1 << SAMPLE_SHIFT;
    private static final int SAMPLE_MASK = SAMPLE_RATE - 1;
    private static final ThreadLocal<SamplerState> SAMPLER = ThreadLocal.withInitial(SamplerState::new);

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

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static void record(int pointCount, int searchMode, int exitKind, long nanos) {
        recordDetailed(null, pointCount, searchMode, exitKind, nanos);
    }

    public static void recordDetailed(String className, int pointCount, int searchMode, int exitKind, long nanos) {
        if (!ENABLED) {
            return;
        }
        recordDetailedWeighted(className, pointCount, searchMode, exitKind, nanos, 1L);
    }

    public static long sampleStart() {
        if (!ENABLED) {
            return 0L;
        }
        if (SAMPLE_MASK == 0) {
            return System.nanoTime();
        }
        SamplerState state = SAMPLER.get();
        state.counter = (state.counter + 1) & SAMPLE_MASK;
        return state.counter == 0 ? System.nanoTime() : 0L;
    }

    public static void recordDetailedSampled(String className, int pointCount, int searchMode, int exitKind, long nanos) {
        if (!ENABLED) {
            return;
        }
        recordDetailedWeighted(className, pointCount, searchMode, exitKind, nanos, SAMPLE_RATE);
    }

    private static void recordDetailedWeighted(String className, int pointCount, int searchMode, int exitKind, long nanos, long weight) {
        long clampedNanos = Math.max(0L, nanos);
        long weightedNanos = clampedNanos * weight;
        CALLS.add(weight);
        TOTAL_NANOS.add(weightedNanos);

        if (searchMode == SEARCH_BINARY) {
            BINARY_CALLS.add(weight);
            BINARY_NANOS.add(weightedNanos);
        } else if (searchMode == SEARCH_LUT) {
            LUT_CALLS.add(weight);
            LUT_NANOS.add(weightedNanos);
        } else {
            LINEAR_CALLS.add(weight);
            LINEAR_NANOS.add(weightedNanos);
        }

        switch (exitKind) {
            case EXIT_LEFT_EXTRAPOLATION -> LEFT_EXTRAPOLATION_CALLS.add(weight);
            case EXIT_RIGHT_EXTRAPOLATION -> RIGHT_EXTRAPOLATION_CALLS.add(weight);
            default -> INTERIOR_CALLS.add(weight);
        }

        if (pointCount <= 2) {
            BUCKET_LE_2_CALLS.add(weight);
            BUCKET_LE_2_NANOS.add(weightedNanos);
        } else if (pointCount <= 4) {
            BUCKET_3_TO_4_CALLS.add(weight);
            BUCKET_3_TO_4_NANOS.add(weightedNanos);
        } else if (pointCount <= 8) {
            BUCKET_5_TO_8_CALLS.add(weight);
            BUCKET_5_TO_8_NANOS.add(weightedNanos);
        } else {
            BUCKET_GE_9_CALLS.add(weight);
            BUCKET_GE_9_NANOS.add(weightedNanos);
        }

        String normalizedClassName = normalizeClassName(className);
        if (!normalizedClassName.isEmpty()) {
            ClassStatsCounter counter = trackedClassStatsCounter(normalizedClassName);
            if (counter != null) {
                counter.record(pointCount, searchMode, exitKind, weightedNanos, weight);
            }
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
                    entry != null ? entry.splineDebug() : "unknown",
                    counter.calls.sum(),
                    counter.linearCalls.sum(),
                    counter.binaryCalls.sum(),
                    counter.lutCalls.sum(),
                    counter.interiorCalls.sum(),
                    counter.leftExtrapolationCalls.sum(),
                    counter.rightExtrapolationCalls.sum(),
                    counter.totalNanos.sum(),
                    new BucketStats(counter.point3Calls.sum(), counter.point3Nanos.sum()),
                    new BucketStats(counter.point4Calls.sum(), counter.point4Nanos.sum()),
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
                             String splineDebug,
                             long calls,
                             long linearCalls,
                             long binaryCalls,
                             long lutCalls,
                             long interiorCalls,
                             long leftExtrapolationCalls,
                             long rightExtrapolationCalls,
                             long totalNanos,
                             BucketStats point3,
                             BucketStats point4,
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
        return base.indexOf('/') < 0 ? base : base.replace('/', '.');
    }

    private static int sampleShift() {
        int configured = Integer.getInteger("dfc.codegen.splineRuntimeStats.sampleShift", 8);
        return Math.max(0, Math.min(20, configured));
    }

    private static ClassStatsCounter trackedClassStatsCounter(String className) {
        ClassStatsCounter existing = CLASS_COUNTERS.get(className);
        if (existing != null) {
            return existing;
        }
        if (CLASS_COUNTERS.size() >= MAX_TRACKED_CLASSES) {
            return null;
        }
        return CLASS_COUNTERS.computeIfAbsent(className, ignored -> new ClassStatsCounter());
    }

    private static final class SamplerState {
        private int counter;
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
        private final LongAdder point3Calls = new LongAdder();
        private final LongAdder point3Nanos = new LongAdder();
        private final LongAdder point4Calls = new LongAdder();
        private final LongAdder point4Nanos = new LongAdder();
        private final LongAdder bucketLe2Calls = new LongAdder();
        private final LongAdder bucketLe2Nanos = new LongAdder();
        private final LongAdder bucket3To4Calls = new LongAdder();
        private final LongAdder bucket3To4Nanos = new LongAdder();
        private final LongAdder bucket5To8Calls = new LongAdder();
        private final LongAdder bucket5To8Nanos = new LongAdder();
        private final LongAdder bucketGe9Calls = new LongAdder();
        private final LongAdder bucketGe9Nanos = new LongAdder();

        private void record(int pointCount, int searchMode, int exitKind, long nanos, long weight) {
            calls.add(weight);
            totalNanos.add(nanos);
            if (searchMode == SEARCH_BINARY) {
                binaryCalls.add(weight);
            } else if (searchMode == SEARCH_LUT) {
                lutCalls.add(weight);
            } else {
                linearCalls.add(weight);
            }
            switch (exitKind) {
                case EXIT_LEFT_EXTRAPOLATION -> leftExtrapolationCalls.add(weight);
                case EXIT_RIGHT_EXTRAPOLATION -> rightExtrapolationCalls.add(weight);
                default -> interiorCalls.add(weight);
            }
            if (pointCount == 3) {
                point3Calls.add(weight);
                point3Nanos.add(nanos);
            } else if (pointCount == 4) {
                point4Calls.add(weight);
                point4Nanos.add(nanos);
            }
            if (pointCount <= 2) {
                bucketLe2Calls.add(weight);
                bucketLe2Nanos.add(nanos);
            } else if (pointCount <= 4) {
                bucket3To4Calls.add(weight);
                bucket3To4Nanos.add(nanos);
            } else if (pointCount <= 8) {
                bucket5To8Calls.add(weight);
                bucket5To8Nanos.add(nanos);
            } else {
                bucketGe9Calls.add(weight);
                bucketGe9Nanos.add(nanos);
            }
        }
    }
}
