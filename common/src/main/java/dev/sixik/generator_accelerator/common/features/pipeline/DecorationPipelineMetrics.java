package dev.sixik.generator_accelerator.common.features.pipeline;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Low-overhead counters for the data-oriented decoration pipeline. */
public final class DecorationPipelineMetrics {

    public static final boolean ENABLED = Boolean.getBoolean("ga.decorationPipeline.metrics");

    public static final int DECORATION_TOTAL_NANOS = 0;
    public static final int DECORATION_NATIVE_NANOS = 1;
    public static final int DECORATION_PARTIAL_NATIVE_NANOS = 2;
    public static final int DECORATION_FALLBACK_NANOS = 3;
    public static final int DECORATION_DESCRIPTOR_NANOS = 4;
    public static final int DECORATION_CANDIDATE_NANOS = 5;
    public static final int DECORATION_COMMIT_NANOS = 6;
    public static final int NATIVE_KERNELS_COMPILED = 7;
    public static final int NATIVE_KERNELS_EXECUTED = 8;
    public static final int PARTIAL_NATIVE_KERNELS_COMPILED = 9;
    public static final int PARTIAL_NATIVE_KERNELS_EXECUTED = 10;
    public static final int PARTIAL_NATIVE_DESCRIPTOR_REJECTED_CALLS = 11;
    public static final int PARTIAL_NATIVE_OPTIMIZED_PLACEMENT_CALLS = 12;
    public static final int NATIVE_CANDIDATES_GENERATED = 13;
    public static final int NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR = 14;
    public static final int NATIVE_CANDIDATES_REJECTED_BY_KERNEL = 15;
    public static final int NATIVE_SECTION_BATCHES = 16;
    public static final int FALLBACK_LEGACY_VM_CALLS = 17;
    public static final int FALLBACK_VANILLA_CALLS = 18;
    public static final int WORLD_SECTION_SWITCHES = 19;
    public static final int WORLD_BLOCK_READS = 20;
    public static final int WORLD_BLOCK_WRITES = 21;
    public static final int ALLOC_RUNTIME_OBJECTS = 22;
    public static final int ALLOC_BUFFER_GROWTHS = 23;
    public static final int ALLOC_FALLBACK_CONTEXT_OBJECTS = 24;
    public static final int SLOW_PATH_OBJECT_ALLOCATING_CALLS = 25;
    public static final int SLOW_PATH_GENERIC_COLLECTION_CALLS = 26;

    public static final int COUNTER_COUNT = 27;

    private static final String[] NAMES = {
            "decoration.totalNanos",
            "decoration.nativeNanos",
            "decoration.partialNativeNanos",
            "decoration.fallbackNanos",
            "decoration.descriptorNanos",
            "decoration.candidateNanos",
            "decoration.commitNanos",
            "native.kernelsCompiled",
            "native.kernelsExecuted",
            "partialNative.kernelsCompiled",
            "partialNative.kernelsExecuted",
            "partialNative.descriptorRejectedCalls",
            "partialNative.optimizedPlacementCalls",
            "native.candidatesGenerated",
            "native.candidatesRejectedByDescriptor",
            "native.candidatesRejectedByKernel",
            "native.sectionBatches",
            "fallback.legacyVmCalls",
            "fallback.vanillaCalls",
            "world.sectionSwitches",
            "world.blockReads",
            "world.blockWrites",
            "alloc.runtimeObjects",
            "alloc.bufferGrowths",
            "alloc.fallbackContextObjects",
            "slowPath.objectAllocatingCalls",
            "slowPath.genericCollectionCalls"
    };

    private static final AtomicLongArray COUNTERS = new AtomicLongArray(COUNTER_COUNT);
    private static final AtomicLongArray KIND_EXECUTIONS = new AtomicLongArray(DecorationKernelKind.values().length);
    private static final AtomicLongArray KIND_NANOS = new AtomicLongArray(DecorationKernelKind.values().length);
    private static final ConcurrentHashMap<String, FeatureMetric> FEATURE_METRICS = new ConcurrentHashMap<>();

    private DecorationPipelineMetrics() {
    }

    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void addElapsed(int counter, long startNanos) {
        if (ENABLED) {
            COUNTERS.addAndGet(counter, System.nanoTime() - startNanos);
        }
    }

    public static void addKindElapsed(DecorationKernelKind kind, long startNanos) {
        if (ENABLED) {
            int index = kind.ordinal();
            KIND_EXECUTIONS.incrementAndGet(index);
            KIND_NANOS.addAndGet(index, System.nanoTime() - startNanos);
        }
    }

    public static void addFeatureElapsed(String featureName, long startNanos) {
        if (ENABLED) {
            FeatureMetric metric = FEATURE_METRICS.computeIfAbsent(featureName, ignored -> new FeatureMetric());
            metric.count.incrementAndGet();
            metric.nanos.addAndGet(System.nanoTime() - startNanos);
        }
    }

    public static void increment(int counter) {
        if (ENABLED) {
            COUNTERS.incrementAndGet(counter);
        }
    }

    public static void add(int counter, long amount) {
        if (ENABLED && amount != 0L) {
            COUNTERS.addAndGet(counter, amount);
        }
    }

    public static long get(int counter) {
        return ENABLED ? COUNTERS.get(counter) : 0L;
    }

    public static void copyTo(long[] out) {
        int limit = out.length < COUNTER_COUNT ? out.length : COUNTER_COUNT;
        for (int i = 0; i < limit; i++) {
            out[i] = ENABLED ? COUNTERS.get(i) : 0L;
        }
    }

    public static void reset() {
        if (ENABLED) {
            for (int i = 0; i < COUNTER_COUNT; i++) {
                COUNTERS.set(i, 0L);
            }
            for (int i = 0; i < DecorationKernelKind.values().length; i++) {
                KIND_EXECUTIONS.set(i, 0L);
                KIND_NANOS.set(i, 0L);
            }
            FEATURE_METRICS.clear();
        }
    }

    public static String name(int counter) {
        return NAMES[counter];
    }

    public static double successfulWritesPerWorldRead() {
        if (!ENABLED) {
            return 0.0D;
        }
        long reads = COUNTERS.get(WORLD_BLOCK_READS);
        if (reads == 0L) {
            return 0.0D;
        }
        return (double) COUNTERS.get(WORLD_BLOCK_WRITES) / (double) reads;
    }

    public static String summary() {
        return "DecorationPipeline metrics: totalDecorationMs=" + millis(DECORATION_TOTAL_NANOS)
                + ", nativeMs=" + millis(DECORATION_NATIVE_NANOS)
                + ", partialNativeMs=" + millis(DECORATION_PARTIAL_NATIVE_NANOS)
                + ", fallbackMs=" + millis(DECORATION_FALLBACK_NANOS)
                + ", descriptorMs=" + millis(DECORATION_DESCRIPTOR_NANOS)
                + ", candidateMs=" + millis(DECORATION_CANDIDATE_NANOS)
                + ", commitMs=" + millis(DECORATION_COMMIT_NANOS)
                + ", nativeKernelsCompiled=" + value(NATIVE_KERNELS_COMPILED)
                + ", nativeKernelsExecuted=" + value(NATIVE_KERNELS_EXECUTED)
                + ", partialNativeKernelsCompiled=" + value(PARTIAL_NATIVE_KERNELS_COMPILED)
                + ", partialNativeKernelsExecuted=" + value(PARTIAL_NATIVE_KERNELS_EXECUTED)
                + ", partialNativeDescriptorRejectedCalls=" + value(PARTIAL_NATIVE_DESCRIPTOR_REJECTED_CALLS)
                + ", partialNativeOptimizedPlacementCalls=" + value(PARTIAL_NATIVE_OPTIMIZED_PLACEMENT_CALLS)
                + ", nativeCandidatesGenerated=" + value(NATIVE_CANDIDATES_GENERATED)
                + ", nativeCandidatesRejectedByDescriptor=" + value(NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR)
                + ", nativeCandidatesRejectedByKernel=" + value(NATIVE_CANDIDATES_REJECTED_BY_KERNEL)
                + ", nativeSectionBatches=" + value(NATIVE_SECTION_BATCHES)
                + ", fallbackLegacyVmCalls=" + value(FALLBACK_LEGACY_VM_CALLS)
                + ", fallbackVanillaCalls=" + value(FALLBACK_VANILLA_CALLS)
                + ", worldSectionSwitches=" + value(WORLD_SECTION_SWITCHES)
                + ", worldBlockReads=" + value(WORLD_BLOCK_READS)
                + ", worldBlockWrites=" + value(WORLD_BLOCK_WRITES)
                + ", successfulWritesPerWorldRead=" + successfulWritesPerWorldRead()
                + ", allocRuntimeObjects=" + value(ALLOC_RUNTIME_OBJECTS)
                + ", allocBufferGrowths=" + value(ALLOC_BUFFER_GROWTHS)
                + ", allocFallbackContextObjects=" + value(ALLOC_FALLBACK_CONTEXT_OBJECTS)
                + ", slowPathObjectAllocatingCalls=" + value(SLOW_PATH_OBJECT_ALLOCATING_CALLS)
                + ", slowPathGenericCollectionCalls=" + value(SLOW_PATH_GENERIC_COLLECTION_CALLS)
                + ", kindBreakdown=" + kindBreakdown()
                + ", featureBreakdown=" + featureBreakdown();
    }

    private static long millis(int counter) {
        return value(counter) / 1_000_000L;
    }

    private static long value(int counter) {
        return ENABLED ? COUNTERS.get(counter) : 0L;
    }

    private static String kindBreakdown() {
        if (!ENABLED) {
            return "";
        }
        StringBuilder builder = new StringBuilder(256);
        DecorationKernelKind[] kinds = DecorationKernelKind.values();
        boolean first = true;
        for (int i = 0; i < kinds.length; i++) {
            long executions = KIND_EXECUTIONS.get(i);
            if (executions == 0L) {
                continue;
            }
            if (!first) {
                builder.append(';');
            }
            first = false;
            builder.append(kinds[i].name())
                    .append(":count=").append(executions)
                    .append(",ms=").append(KIND_NANOS.get(i) / 1_000_000L);
        }
        return builder.toString();
    }

    private static String featureBreakdown() {
        if (!ENABLED || FEATURE_METRICS.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(512);
        FeatureMetric[] top = new FeatureMetric[8];
        String[] names = new String[8];
        FEATURE_METRICS.forEach((name, metric) -> {
            long nanos = metric.nanos.get();
            for (int i = 0; i < top.length; i++) {
                FeatureMetric current = top[i];
                if (current == null || nanos > current.nanos.get()) {
                    for (int j = top.length - 1; j > i; j--) {
                        top[j] = top[j - 1];
                        names[j] = names[j - 1];
                    }
                    top[i] = metric;
                    names[i] = name;
                    break;
                }
            }
        });
        for (int i = 0; i < top.length && top[i] != null; i++) {
            if (i > 0) {
                builder.append(';');
            }
            builder.append(names[i])
                    .append(":count=").append(top[i].count.get())
                    .append(",ms=").append(top[i].nanos.get() / 1_000_000L);
        }
        return builder.toString();
    }

    private static final class FeatureMetric {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();
    }
}
