package dev.sixik.generator_accelerator.common.features.vm;

import java.util.concurrent.atomic.LongAdder;

public final class FeatureVmMetrics {
    private static final LongAdder PROGRAMS_COMPILED = new LongAdder();
    private static final LongAdder FAST_OPS_COMPILED = new LongAdder();
    private static final LongAdder FALLBACK_OPS_COMPILED = new LongAdder();
    private static final LongAdder PROGRAM_EXECUTIONS = new LongAdder();
    private static final LongAdder FAST_OP_EXECUTIONS = new LongAdder();
    private static final LongAdder FALLBACK_OP_EXECUTIONS = new LongAdder();
    private static final LongAdder FEATURE_PLACE_CALLS = new LongAdder();

    private FeatureVmMetrics() {
    }

    static void recordProgramCompiled(int fastOps, int fallbackOps) {
        PROGRAMS_COMPILED.increment();
        FAST_OPS_COMPILED.add(fastOps);
        FALLBACK_OPS_COMPILED.add(fallbackOps);
    }

    static void recordProgramExecution() {
        PROGRAM_EXECUTIONS.increment();
    }

    static void recordFastOpExecution() {
        FAST_OP_EXECUTIONS.increment();
    }

    static void recordFallbackOpExecution() {
        FALLBACK_OP_EXECUTIONS.increment();
    }

    static void recordFeaturePlaceCall() {
        FEATURE_PLACE_CALLS.increment();
    }

    public static long programsCompiled() {
        return PROGRAMS_COMPILED.sum();
    }

    public static long fastOpsCompiled() {
        return FAST_OPS_COMPILED.sum();
    }

    public static long fallbackOpsCompiled() {
        return FALLBACK_OPS_COMPILED.sum();
    }

    public static long programExecutions() {
        return PROGRAM_EXECUTIONS.sum();
    }

    public static long fastOpExecutions() {
        return FAST_OP_EXECUTIONS.sum();
    }

    public static long fallbackOpExecutions() {
        return FALLBACK_OP_EXECUTIONS.sum();
    }

    public static long featurePlaceCalls() {
        return FEATURE_PLACE_CALLS.sum();
    }
}
