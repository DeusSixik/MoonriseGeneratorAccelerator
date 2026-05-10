package dev.sixik.generator_accelerator.common.features.vm;

import java.util.concurrent.atomic.LongAdder;

@Deprecated(forRemoval = false)
public final class FeatureVmMetrics {
    public static final boolean ENABLED = Boolean.getBoolean("ga.featureVm.metrics");

    private static final LongAdder PROGRAMS_COMPILED = new LongAdder();
    private static final LongAdder FAST_OPS_COMPILED = new LongAdder();
    private static final LongAdder FALLBACK_OPS_COMPILED = new LongAdder();
    private static final LongAdder PROGRAM_EXECUTIONS = new LongAdder();
    private static final LongAdder LINEAR_FAST_EXECUTIONS = new LongAdder();
    private static final LongAdder BUFFER_FAST_EXECUTIONS = new LongAdder();
    private static final LongAdder FAST_OP_EXECUTIONS = new LongAdder();
    private static final LongAdder FALLBACK_OP_EXECUTIONS = new LongAdder();
    private static final LongAdder FEATURE_PLACE_CALLS = new LongAdder();
    private static final LongAdder TOTAL_EXECUTION_NANOS = new LongAdder();

    @Deprecated(forRemoval = false)
    private FeatureVmMetrics() {
    }

    static void recordProgramCompiled(int fastOps, int fallbackOps) {
        if (!ENABLED) return;
        PROGRAMS_COMPILED.increment();
        FAST_OPS_COMPILED.add(fastOps);
        FALLBACK_OPS_COMPILED.add(fallbackOps);
    }

    static void recordProgramExecution() {
        if (!ENABLED) return;
        PROGRAM_EXECUTIONS.increment();
    }

    static void recordLinearFastExecution() {
        if (!ENABLED) return;
        LINEAR_FAST_EXECUTIONS.increment();
    }

    static void recordBufferFastExecution() {
        if (!ENABLED) return;
        BUFFER_FAST_EXECUTIONS.increment();
    }

    static void recordFastOpExecution() {
        if (!ENABLED) return;
        FAST_OP_EXECUTIONS.increment();
    }

    static void recordFastOpExecutions(long count) {
        if (!ENABLED) return;
        FAST_OP_EXECUTIONS.add(count);
    }

    static void recordFallbackOpExecution() {
        if (!ENABLED) return;
        FALLBACK_OP_EXECUTIONS.increment();
    }

    static void recordFeaturePlaceCall() {
        if (!ENABLED) return;
        FEATURE_PLACE_CALLS.increment();
    }

    static void recordFeaturePlaceCalls(long count) {
        if (!ENABLED) return;
        FEATURE_PLACE_CALLS.add(count);
    }

    static void recordExecutionNanos(long nanos) {
        if (!ENABLED) return;
        TOTAL_EXECUTION_NANOS.add(nanos);
    }

    public static void reset() {
        if (!ENABLED) return;
        PROGRAMS_COMPILED.reset();
        FAST_OPS_COMPILED.reset();
        FALLBACK_OPS_COMPILED.reset();
        PROGRAM_EXECUTIONS.reset();
        LINEAR_FAST_EXECUTIONS.reset();
        BUFFER_FAST_EXECUTIONS.reset();
        FAST_OP_EXECUTIONS.reset();
        FALLBACK_OP_EXECUTIONS.reset();
        FEATURE_PLACE_CALLS.reset();
        TOTAL_EXECUTION_NANOS.reset();
    }

    @Deprecated(forRemoval = false)
    public static long programsCompiled() {
        return PROGRAMS_COMPILED.sum();
    }

    @Deprecated(forRemoval = false)
    public static long fastOpsCompiled() {
        return FAST_OPS_COMPILED.sum();
    }

    @Deprecated(forRemoval = false)
    public static long fallbackOpsCompiled() {
        return FALLBACK_OPS_COMPILED.sum();
    }

    @Deprecated(forRemoval = false)
    public static long programExecutions() {
        return PROGRAM_EXECUTIONS.sum();
    }

    @Deprecated(forRemoval = false)
    public static long linearFastExecutions() {
        return LINEAR_FAST_EXECUTIONS.sum();
    }

    @Deprecated(forRemoval = false)
    public static long bufferFastExecutions() {
        return BUFFER_FAST_EXECUTIONS.sum();
    }

    @Deprecated(forRemoval = false)
    public static long fastOpExecutions() {
        return FAST_OP_EXECUTIONS.sum();
    }

    @Deprecated(forRemoval = false)
    public static long fallbackOpExecutions() {
        return FALLBACK_OP_EXECUTIONS.sum();
    }

    @Deprecated(forRemoval = false)
    public static long featurePlaceCalls() {
        return FEATURE_PLACE_CALLS.sum();
    }

    @Deprecated(forRemoval = false)
    public static long totalExecutionNanos() {
        return TOTAL_EXECUTION_NANOS.sum();
    }

    @Deprecated(forRemoval = false)
    public static String summary() {
        long executions = programExecutions();
        long nanos = totalExecutionNanos();
        long avgNanos = executions == 0 ? 0 : nanos / executions;
        return "FeatureVM metrics: programsCompiled=" + programsCompiled()
                + ", fastOpsCompiled=" + fastOpsCompiled()
                + ", fallbackOpsCompiled=" + fallbackOpsCompiled()
                + ", executions=" + executions
                + ", linearFastExecutions=" + linearFastExecutions()
                + ", bufferFastExecutions=" + bufferFastExecutions()
                + ", fastOpExecutions=" + fastOpExecutions()
                + ", fallbackOpExecutions=" + fallbackOpExecutions()
                + ", featurePlaceCalls=" + featurePlaceCalls()
                + ", totalExecutionMs=" + (nanos / 1_000_000L)
                + ", avgExecutionNs=" + avgNanos;
    }
}
