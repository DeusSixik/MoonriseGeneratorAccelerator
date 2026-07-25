package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;
import net.sixik.ga_utils.javatogpu.api.GpuBackendTarget;
import net.sixik.ga_utils.javatogpu.api.GpuPreparedLauncher;
import net.sixik.ga_utils.javatogpu.api.GpuScope;
import net.sixik.ga_utils.javatogpu.api.JavaToGpu;
import net.sixik.ga_utils.javatogpu.api.observability.GpuPreparedInvocationTimings;
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeCompileOptions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Optional batch executor for {@link GpuIrPayload}. CPU mirror remains the default path. */
public final class GpuPayloadBatchExecutor {
    public static final String GPU_ENABLED_PROPERTY = "ga.dfc.gpu";
    public static final String SERIALIZE_RUNTIME_PROPERTY = "ga.dfc.gpu.serializeRuntime";
    public static final String PERSISTENT_RUNTIME_SCOPE_PROPERTY = "ga.dfc.gpu.persistentRuntimeScope";
    public static final String OPPORTUNISTIC_RUNTIME_LOCK_PROPERTY = "ga.dfc.gpu.opportunisticRuntimeLock";
    public static final String RUNTIME_LOCK_WAIT_NANOS_PROPERTY = "ga.dfc.gpu.runtimeLockWaitNanos";
    public static final String RUNTIME_MICRO_BATCH_MAX_PROPERTY = "ga.dfc.gpu.runtimeMicroBatchMax";
    public static final String RUNTIME_MICRO_BATCH_MIN_PROPERTY = "ga.dfc.gpu.runtimeMicroBatchMin";
    public static final String RUNTIME_MICRO_BATCH_COLLECT_NANOS_PROPERTY = "ga.dfc.gpu.runtimeMicroBatchCollectNanos";
    public static final String RUNTIME_MICRO_BATCH_WAIT_NANOS_PROPERTY = "ga.dfc.gpu.runtimeMicroBatchWaitNanos";
    public static final String RUNTIME_MICRO_BATCH_BACKOFF_SINGLE_STREAK_PROPERTY = "ga.dfc.gpu.runtimeMicroBatchBackoffSingleStreak";
    public static final String RUNTIME_MICRO_BATCH_BACKOFF_BUSY_STREAK_PROPERTY = "ga.dfc.gpu.runtimeMicroBatchBackoffBusyStreak";
    public static final String RUNTIME_MICRO_BATCH_BACKOFF_BATCHES_PROPERTY = "ga.dfc.gpu.runtimeMicroBatchBackoffBatches";
    public static final String DIRECT_GENERATED_LAUNCHER_PROPERTY = "ga.dfc.gpu.directGeneratedLauncher";
    public static final String RUNTIME_BATCH_MAX_PROPERTY = "ga.dfc.gpu.runtimeBatchMax";
    public static final String RUNTIME_MIN_POINTS_PROPERTY = "ga.dfc.gpu.runtimeMinPoints";
    public static final String RUNTIME_PARITY_BATCHES_PROPERTY = "ga.dfc.gpu.runtimeParityBatches";
    public static final String RUNTIME_PARITY_EPSILON_PROPERTY = "ga.dfc.gpu.runtimeParityEpsilon";
    public static final String PREPARED_LAUNCHER_CACHE_MAX_PROPERTY = "ga.dfc.gpu.preparedLauncherCacheMax";

    private static final String METHOD_NAME = "computeBatch";
    private static final String MULTI_METHOD_NAME = "computeMultiPayloadBatch";
    private static final String INVOKER_CLASS = "net.sixik.ga_utils.javatogpu.runtime.GpuGeneratedLauncherInvoker";
    private static final String DIRECT_LAUNCHER_CLASS = "dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.generated.GpuPayloadArithmeticKernel_computeBatch_GpuLauncher";
    private static final String RUNTIME_CLASS = "net.sixik.ga_utils.javatogpu.runtime.GpuRuntime";
    private static final String OPTIONS_CLASS = "net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeCompileOptions";
    private static final String BACKEND_TARGET_CLASS = "net.sixik.ga_utils.javatogpu.api.GpuBackendTarget";
    private static final Object PREFLIGHT_LOCK = new Object();
    private static final ReentrantLock GPU_RUNTIME_LOCK = new ReentrantLock();
    private static final AtomicReference<String> LIFECYCLE_DISABLED_REASON = new AtomicReference<>("none");
    private static final java.util.concurrent.atomic.AtomicInteger RUNTIME_BATCHES_REMAINING =
            new java.util.concurrent.atomic.AtomicInteger(runtimeBatchBudget());
    private static final java.util.concurrent.atomic.AtomicInteger RUNTIME_PARITY_REMAINING =
            new java.util.concurrent.atomic.AtomicInteger(runtimeParityBudget());
    private static final java.util.concurrent.atomic.AtomicInteger RUNTIME_MICRO_BATCH_SINGLE_STREAK =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger RUNTIME_MICRO_BATCH_BUSY_STREAK =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger RUNTIME_MICRO_BATCH_BACKOFF_REMAINING =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<BatchBuffers> BATCH_BUFFERS = ThreadLocal.withInitial(BatchBuffers::new);
    private static final ThreadLocal<RuntimeMicroBatchBuffers> RUNTIME_MICRO_BATCH_BUFFERS =
            ThreadLocal.withInitial(RuntimeMicroBatchBuffers::new);
    private static final ConcurrentLinkedQueue<RuntimeMicroBatchRequest> RUNTIME_MICRO_BATCH_QUEUE =
            new ConcurrentLinkedQueue<>();
    private static volatile PreflightState preflightState = PreflightState.NOT_RUN;
    private static volatile String preflightReason = "not-run";
    private static volatile Object persistentRuntimeScope;
    private static volatile Object persistentRuntimeBackend;
    private static volatile PreparedLauncherState preparedLauncherState;
    private static final LinkedHashMap<PreparedLauncherCacheKey, PreparedLauncherState> PREPARED_LAUNCHER_CACHE =
            new LinkedHashMap<>(16, 0.75F, true);
    private static final LinkedHashMap<MultiPreparedLauncherCacheKey, MultiPreparedLauncherState> MULTI_PREPARED_LAUNCHER_CACHE =
            new LinkedHashMap<>(16, 0.75F, true);

    private GpuPayloadBatchExecutor() {
    }

    public static Execution compute(GpuIrPayload payload, int[] blockX, int[] blockY, int[] blockZ, double[] output) {
        return compute(payload, blockX, blockY, blockZ, emptyExternValues(payload), output);
    }

    public static Execution compute(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output) {
        validate(payload, blockX, blockY, blockZ, output, externValues);
        if (shouldAttemptGpu()) {
            GpuAttempt attempt = tryComputeGpu(payload, blockX, blockY, blockZ, externValues, output);
            if (attempt.success()) {
                return new Execution(Backend.GPU, "none");
            }
            if (attempt.disablesGpu()) {
                disableGpuForLifecycle(attempt.failureReason());
            }
            computeCpu(payload, blockX, blockY, blockZ, externValues, output);
            return new Execution(Backend.CPU, attempt.failureReason());
        }

        computeCpu(payload, blockX, blockY, blockZ, externValues, output);
        return new Execution(Backend.CPU, disabledReason());
    }

    public static boolean shouldAttemptGpu() {
        if (!Boolean.getBoolean(GPU_ENABLED_PROPERTY) || !"none".equals(LIFECYCLE_DISABLED_REASON.get())) {
            return false;
        }
        PreflightResult result = ensurePreflightPassed();
        if (!result.passed()) {
            disableGpuForLifecycle(result.reason());
            return false;
        }
        return true;
    }

    public static void disableGpuForLifecycle(String reason) {
        LIFECYCLE_DISABLED_REASON.compareAndSet("none", normalizeReason(reason));
    }

    public static String disabledReason() {
        if (!Boolean.getBoolean(GPU_ENABLED_PROPERTY)) {
            return "disabled";
        }
        return LIFECYCLE_DISABLED_REASON.get();
    }

    public static void resetRuntimeState() {
        GPU_RUNTIME_LOCK.lock();
        try {
            closePreparedLauncher();
            closePersistentRuntimeScope();
            LIFECYCLE_DISABLED_REASON.set("none");
        } finally {
            GPU_RUNTIME_LOCK.unlock();
        }
        synchronized (PREFLIGHT_LOCK) {
            preflightState = PreflightState.NOT_RUN;
            preflightReason = "not-run";
        }
        RUNTIME_PARITY_REMAINING.set(runtimeParityBudget());
        RUNTIME_BATCHES_REMAINING.set(runtimeBatchBudget());
        RUNTIME_MICRO_BATCH_SINGLE_STREAK.set(0);
        RUNTIME_MICRO_BATCH_BUSY_STREAK.set(0);
        RUNTIME_MICRO_BATCH_BACKOFF_REMAINING.set(0);
    }

    public static String preflightStateName() {
        return preflightState.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static String preflightReason() {
        return preflightReason;
    }

    public static boolean persistentRuntimeScopeEnabled() {
        return booleanProperty(PERSISTENT_RUNTIME_SCOPE_PROPERTY, true);
    }

    public static boolean persistentRuntimeScopeActive() {
        return persistentRuntimeScope != null;
    }

    public static boolean directGeneratedLauncherEnabled() {
        return booleanProperty(DIRECT_GENERATED_LAUNCHER_PROPERTY, true);
    }

    public static boolean preparedLauncherEnabled() {
        return true;
    }

    public static String runtimeApiLocation() {
        try {
            java.security.CodeSource codeSource = JavaToGpu.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return "unknown";
            }
            return codeSource.getLocation().toString();
        } catch (RuntimeException | LinkageError exception) {
            return exception.toString();
        }
    }

    public static String preparedLauncherStaticArguments() {
        PreparedLauncherState state = preparedLauncherState;
        if (state == null) {
            return "none";
        }
        try {
            return state.launcher().staticArgumentNames().toString();
        } catch (RuntimeException | LinkageError exception) {
            return exception.toString();
        }
    }

    public static String preparedLauncherDynamicArguments() {
        PreparedLauncherState state = preparedLauncherState;
        if (state == null) {
            return "none";
        }
        try {
            return state.launcher().dynamicArgumentNames().toString();
        } catch (RuntimeException | LinkageError exception) {
            return exception.toString();
        }
    }

    public static int runtimeParityRemaining() {
        return RUNTIME_PARITY_REMAINING.get();
    }

    public static double runtimeParityEpsilon() {
        return doubleProperty(RUNTIME_PARITY_EPSILON_PROPERTY, 1.0E-9D);
    }

    public static int runtimeBatchBudgetMax() {
        return runtimeBatchBudget();
    }

    public static int runtimeBatchRemaining() {
        return RUNTIME_BATCHES_REMAINING.get();
    }

    public static boolean runtimeLaunchWouldSkipForBusyLock() {
        if (!(booleanProperty(SERIALIZE_RUNTIME_PROPERTY, true) || persistentRuntimeScopeEnabled())) {
            return false;
        }
        if (!booleanProperty(OPPORTUNISTIC_RUNTIME_LOCK_PROPERTY, true) || runtimeLockWaitNanos() > 0L) {
            return false;
        }
        return GPU_RUNTIME_LOCK.isLocked() && !GPU_RUNTIME_LOCK.isHeldByCurrentThread();
    }

    public static boolean shouldAttemptRuntimeBatch() {
        return shouldAttemptRuntimeBatch(0);
    }

    public static boolean shouldAttemptRuntimeBatch(int pointCount) {
        return shouldAttemptRuntimeBatch(pointCount, false);
    }

    public static boolean shouldAttemptRuntimeBatchAllowingSmallPrototype(int pointCount) {
        return shouldAttemptRuntimeBatch(pointCount, true);
    }

    private static boolean shouldAttemptRuntimeBatch(int pointCount, boolean allowBelowMinPoints) {
        if (!Boolean.getBoolean(GPU_ENABLED_PROPERTY)) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("disabled");
            return false;
        }
        if (!"none".equals(LIFECYCLE_DISABLED_REASON.get())) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("lifecycle_disabled");
            return false;
        }
        if (pointCount > 0 && pointCount < runtimeMinPoints()) {
            if (allowBelowMinPoints) {
                if (!runtimeMicroBatchCanReachMinPoints(pointCount)) {
                    RouterPipeline.recordGpuPayloadBatchRuntimeGate("prototype_below_min_unreachable");
                    return false;
                }
                RouterPipeline.recordGpuPayloadBatchRuntimeGate("prototype_below_min_allowed");
            } else if (!runtimeMicroBatchCanReachMinPoints(pointCount)) {
                RouterPipeline.recordGpuPayloadBatchRuntimeGate("min_points");
                return false;
            } else {
                RouterPipeline.recordGpuPayloadBatchRuntimeGate("microbatch_below_min");
            }
        }
        if (consumeRuntimeMicroBatchBackoff()) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("backoff");
            RouterPipeline.recordGpuPayloadBatchRuntimeBackoffSkip();
            return false;
        }
        if (!claimRuntimeBatch()) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("budget");
            return false;
        }
        PreflightResult result = ensurePreflightPassed();
        if (!result.passed()) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("preflight_failed");
            disableGpuForLifecycle(result.reason());
            return false;
        }
        return true;
    }

    public static DebugProbeResult runDebugProbe() {
        resetRuntimeState();
        if (!Boolean.getBoolean(GPU_ENABLED_PROPERTY)) {
            return debugProbeResult(false, "disabled", 0, 0.0D, Double.NaN, Double.NaN);
        }
        if (!shouldAttemptGpu()) {
            return debugProbeResult(false, disabledReason(), 0, 0.0D, Double.NaN, Double.NaN);
        }

        GpuIrPayload payload = preflightPayload();
        int[] blockX = new int[]{4, -8, 0, 16, 31, -32, 7, 128};
        int[] blockY = new int[]{10, 3, -64, 0, 255, -1, 96, 12};
        int[] blockZ = new int[]{8, -12, 16, -24, 63, 5, -96, 0};
        double[] gpu = new double[blockX.length];
        GpuAttempt attempt = tryComputeGpu(payload, blockX, blockY, blockZ, gpu);
        if (!attempt.success()) {
            disableGpuForLifecycle(attempt.failureReason());
            return debugProbeResult(false, attempt.failureReason(), blockX.length, 0.0D, Double.NaN, Double.NaN);
        }

        double[] expected = new double[blockX.length];
        computeCpu(payload, blockX, blockY, blockZ, expected);
        RuntimeParityReport parity = compareRuntimeParity(gpu, expected, runtimeParityEpsilon());
        if (!parity.passed()) {
            disableGpuForLifecycle(parity.failureReason());
        }
        return debugProbeResult(
                parity.passed(),
                parity.passed() ? "passed" : parity.failureReason(),
                blockX.length,
                parity.maxAbsError(),
                gpu[0],
                expected[0]);
    }

    public static LargeBatchProbeResult runLargeBatchProbe() {
        resetRuntimeState();
        if (!Boolean.getBoolean(GPU_ENABLED_PROPERTY)) {
            return largeBatchProbeResult(false, "disabled", new LargeBatchProbeSample[0]);
        }
        if (!shouldAttemptGpu()) {
            return largeBatchProbeResult(false, disabledReason(), new LargeBatchProbeSample[0]);
        }

        GpuIrPayload payload = preflightPayload();
        int[] pointCounts = new int[]{128, 512, 2048, 8192, 32768};
        LargeBatchProbeSample[] samples = new LargeBatchProbeSample[pointCounts.length];
        boolean success = true;
        String reason = "passed";
        for (int sampleIndex = 0; sampleIndex < pointCounts.length; sampleIndex++) {
            LargeBatchProbeSample sample = runLargeBatchProbeSample(payload, pointCounts[sampleIndex]);
            samples[sampleIndex] = sample;
            if (!sample.success() && success) {
                success = false;
                reason = sample.reason();
            }
        }
        return largeBatchProbeResult(success, reason, samples);
    }

    private static LargeBatchProbeSample runLargeBatchProbeSample(GpuIrPayload payload, int points) {
        BatchBuffers buffers = localBuffers(points, payload.nodeCount(), payload.externInputCount());
        int[] blockX = buffers.blockX();
        int[] blockY = buffers.blockY();
        int[] blockZ = buffers.blockZ();
        for (int i = 0; i < points; i++) {
            blockX[i] = (i & 15) - 8 + ((i >> 8) & 31);
            blockY[i] = ((i >> 4) & 31) - 16;
            blockZ[i] = ((i >> 9) & 63) - 32 + ((i & 3) * 16);
        }

        double[] gpu = new double[points];
        long gpuStart = System.nanoTime();
        GpuAttempt attempt = tryComputeGpu(payload, blockX, blockY, blockZ, buffers.externValues(), gpu, buffers.scratch());
        long gpuNanos = System.nanoTime() - gpuStart;
        if (!attempt.success()) {
            return new LargeBatchProbeSample(
                    false,
                    attempt.failureReason(),
                    points,
                    gpuNanos,
                    0L,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    false,
                    "skipped-java-to-gpu-failed",
                    0L,
                    Double.NaN,
                    Double.NaN,
                    false,
                    "skipped-java-to-gpu-failed",
                    0L,
                    Double.NaN,
                    Double.NaN);
        }

        double[] expected = new double[points];
        long cpuStart = System.nanoTime();
        computeCpu(payload, blockX, blockY, blockZ, buffers.externValues(), expected);
        long cpuNanos = System.nanoTime() - cpuStart;
        RuntimeParityReport parity = compareRuntimeParity(gpu, expected, runtimeParityEpsilon());

        double[] warmGpu = new double[points];
        long warmStart = System.nanoTime();
        GpuAttempt warmAttempt = tryComputeGpu(payload, blockX, blockY, blockZ, buffers.externValues(), warmGpu, buffers.scratch());
        long warmGpuNanos = System.nanoTime() - warmStart;
        RuntimeParityReport warmParity = warmAttempt.success()
                ? compareRuntimeParity(warmGpu, expected, runtimeParityEpsilon())
                : RuntimeParityReport.failed(points, 0.0D, warmAttempt.failureReason());

        double[] directGpu = new double[points];
        long directStart = System.nanoTime();
        GpuAttempt directAttempt = GpuPayloadDirectOpenClExecutor.tryComputeGpu(
                payload, blockX, blockY, blockZ, buffers.externValues(), directGpu);
        long directGpuNanos = System.nanoTime() - directStart;
        RuntimeParityReport directParity = directAttempt.success()
                ? compareRuntimeParity(directGpu, expected, runtimeParityEpsilon())
                : RuntimeParityReport.failed(points, 0.0D, directAttempt.failureReason());
        return new LargeBatchProbeSample(
                parity.passed() && warmAttempt.success() && warmParity.passed(),
                parity.passed() && warmAttempt.success() && warmParity.passed()
                        ? "passed"
                        : (!parity.passed() ? parity.failureReason() : warmParity.failureReason()),
                points,
                gpuNanos,
                cpuNanos,
                parity.maxAbsError(),
                gpu.length == 0 ? Double.NaN : gpu[0],
                expected.length == 0 ? Double.NaN : expected[0],
                warmAttempt.success() && warmParity.passed(),
                warmAttempt.success() && warmParity.passed() ? "passed" : warmParity.failureReason(),
                warmGpuNanos,
                warmParity.maxAbsError(),
                warmGpu.length == 0 ? Double.NaN : warmGpu[0],
                directAttempt.success() && directParity.passed(),
                directAttempt.success() && directParity.passed() ? "passed" : directParity.failureReason(),
                directGpuNanos,
                directParity.maxAbsError(),
                directGpu.length == 0 ? Double.NaN : directGpu[0]);
    }

    private static DebugProbeResult debugProbeResult(
            boolean success,
            String reason,
            int points,
            double maxAbsError,
            double firstGpuValue,
            double firstCpuValue) {
        return new DebugProbeResult(
                success,
                normalizeReason(reason),
                Boolean.getBoolean(GPU_ENABLED_PROPERTY),
                persistentRuntimeScopeEnabled(),
                persistentRuntimeScopeActive(),
                preflightStateName(),
                preflightReason(),
                disabledReason(),
                points,
                maxAbsError,
                firstGpuValue,
                firstCpuValue);
    }

    private static LargeBatchProbeResult largeBatchProbeResult(
            boolean success,
            String reason,
            LargeBatchProbeSample[] samples) {
        return new LargeBatchProbeResult(
                success,
                normalizeReason(reason),
                Boolean.getBoolean(GPU_ENABLED_PROPERTY),
                preparedLauncherEnabled(),
                directGeneratedLauncherEnabled(),
                persistentRuntimeScopeEnabled(),
                persistentRuntimeScopeActive(),
                preflightStateName(),
                preflightReason(),
                disabledReason(),
                samples);
    }

    public static BatchBuffers localBuffers(int pointCount, int nodeCount) {
        return localBuffers(pointCount, nodeCount, 0);
    }

    public static BatchBuffers localBuffers(int pointCount, int nodeCount, int externInputCount) {
        if (pointCount < 0) {
            throw new IllegalArgumentException("pointCount must be non-negative: " + pointCount);
        }
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("nodeCount must be positive: " + nodeCount);
        }
        if (externInputCount < 0) {
            throw new IllegalArgumentException("externInputCount must be non-negative: " + externInputCount);
        }
        BatchBuffers buffers = BATCH_BUFFERS.get();
        buffers.ensure(
                pointCount,
                Math.multiplyExact(pointCount, nodeCount),
                Math.multiplyExact(pointCount, externInputCount));
        return buffers;
    }

    public static RuntimeParityReport checkRuntimeParity(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] gpuOutput) {
        double[] expected = new double[gpuOutput.length];
        return checkRuntimeParity(payload, blockX, blockY, blockZ, emptyExternValues(payload), gpuOutput, expected);
    }

    public static RuntimeParityReport checkRuntimeParity(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] gpuOutput,
            double[] expected) {
        return checkRuntimeParity(payload, blockX, blockY, blockZ, emptyExternValues(payload), gpuOutput, expected);
    }

    public static RuntimeParityReport checkRuntimeParity(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] gpuOutput,
            double[] expected) {
        validate(payload, blockX, blockY, blockZ, gpuOutput, externValues);
        Objects.requireNonNull(expected, "expected");
        if (expected.length < gpuOutput.length) {
            throw new IllegalArgumentException("Expected buffer length " + expected.length
                    + " is smaller than GPU output length " + gpuOutput.length);
        }
        if (!claimRuntimeParityCheck()) {
            return RuntimeParityReport.skipped();
        }

        double epsilon = runtimeParityEpsilon();
        double maxAbsError = 0.0D;
        for (int i = 0; i < gpuOutput.length; i++) {
            double mirror = GpuPayloadCpuEvaluator.compute(payload, blockX[i], blockY[i], blockZ[i], externValues, i);
            expected[i] = mirror;

            double actual = gpuOutput[i];
            if (runtimeSameValue(actual, mirror)) {
                continue;
            }
            double diff = runtimeAbsError(actual, mirror);
            if (diff > maxAbsError) {
                maxAbsError = diff;
            }
            if (!(diff <= epsilon)) {
                return RuntimeParityReport.failed(
                        gpuOutput.length,
                        maxAbsError,
                        "runtime gpu/payload parity mismatch at index " + i
                                + ": x=" + blockX[i]
                                + ", y=" + blockY[i]
                                + ", z=" + blockZ[i]
                                + ", gpu=" + actual
                                + ", payloadMirror=" + mirror
                                + ", diff=" + diff
                                + ", epsilon=" + epsilon
                                + ", nodes=" + payload.nodeCount()
                                + ", rootIndex=" + payload.rootIndex()
                                + ", rootOpcode=" + payloadRootOpcode(payload)
                                + ", nativeNoiseOctaves=" + payload.noiseOctaveCount()
                                + ", externInputs=" + payload.externInputCount());
            }
        }
        return RuntimeParityReport.passed(gpuOutput.length, maxAbsError);
    }

    private static int payloadRootOpcode(GpuIrPayload payload) {
        int rootIndex = payload.rootIndex();
        int[] opcodes = payload.opcodes();
        return rootIndex >= 0 && rootIndex < opcodes.length ? opcodes[rootIndex] : Integer.MIN_VALUE;
    }

    public static RuntimeParityReport checkRuntimeParityAgainstExpected(double[] gpuOutput, double[] expected) {
        return checkRuntimeParityAgainstExpected(gpuOutput, expected, gpuOutput.length);
    }

    public static RuntimeParityReport checkRuntimeParityAgainstExpected(double[] gpuOutput, double[] expected, int points) {
        Objects.requireNonNull(gpuOutput, "gpuOutput");
        Objects.requireNonNull(expected, "expected");
        if (points < 0 || points > gpuOutput.length) {
            throw new IllegalArgumentException("Point count " + points
                    + " is outside GPU output length " + gpuOutput.length);
        }
        if (expected.length < points) {
            throw new IllegalArgumentException("Expected buffer length " + expected.length
                    + " is smaller than checked point count " + points);
        }
        if (!claimRuntimeParityCheck()) {
            return RuntimeParityReport.skipped();
        }
        return compareRuntimeParity(gpuOutput, expected, runtimeParityEpsilon(), points);
    }

    public static RuntimeParityReport checkRuntimeParityAgainstRootExpected(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] gpuOutput,
            double[] rootExpected,
            double[] payloadExpected,
            int points) {
        validate(payload, blockX, blockY, blockZ, gpuOutput, externValues);
        Objects.requireNonNull(rootExpected, "rootExpected");
        Objects.requireNonNull(payloadExpected, "payloadExpected");
        if (points < 0 || points > gpuOutput.length) {
            throw new IllegalArgumentException("Point count " + points
                    + " is outside GPU output length " + gpuOutput.length);
        }
        if (rootExpected.length < points || payloadExpected.length < points) {
            throw new IllegalArgumentException("Parity buffers are smaller than checked point count " + points);
        }
        if (!claimRuntimeParityCheck()) {
            return RuntimeParityReport.skipped();
        }

        double maxAbsError = 0.0D;
        for (int i = 0; i < points; i++) {
            double payloadMirror = GpuPayloadCpuEvaluator.compute(
                    payload, blockX[i], blockY[i], blockZ[i], externValues, i);
            payloadExpected[i] = payloadMirror;

            double gpu = gpuOutput[i];
            double gpuPayloadDiff = runtimeAbsError(gpu, payloadMirror);
            if (gpuPayloadDiff > maxAbsError) {
                maxAbsError = gpuPayloadDiff;
            }
            if (!runtimeSameValue(gpu, payloadMirror) && !(gpuPayloadDiff <= runtimeParityEpsilon())) {
                return RuntimeParityReport.failed(
                        points,
                        maxAbsError,
                        "runtime gpu/payload parity mismatch at index " + i
                                + ": x=" + blockX[i]
                                + ", y=" + blockY[i]
                                + ", z=" + blockZ[i]
                                + ", gpu=" + gpu
                                + ", payloadMirror=" + payloadMirror
                                + ", rootMirror=" + rootExpected[i]
                                + ", diff=" + gpuPayloadDiff
                                + ", epsilon=" + runtimeParityEpsilon());
            }

            double rootMirror = rootExpected[i];
            double payloadRootDiff = runtimeAbsError(payloadMirror, rootMirror);
            if (payloadRootDiff > maxAbsError) {
                maxAbsError = payloadRootDiff;
            }
            if (!runtimeSameValue(payloadMirror, rootMirror) && !(payloadRootDiff <= runtimeParityEpsilon())) {
                return RuntimeParityReport.failed(
                        points,
                        maxAbsError,
                        "runtime payload/root parity mismatch at index " + i
                                + ": x=" + blockX[i]
                                + ", y=" + blockY[i]
                                + ", z=" + blockZ[i]
                                + ", payloadMirror=" + payloadMirror
                                + ", rootMirror=" + rootMirror
                                + ", gpu=" + gpu
                                + ", diff=" + payloadRootDiff
                                + ", epsilon=" + runtimeParityEpsilon());
            }
        }
        return RuntimeParityReport.passed(points, maxAbsError);
    }

    private static RuntimeParityReport compareRuntimeParity(double[] gpuOutput, double[] expected, double epsilon) {
        return compareRuntimeParity(gpuOutput, expected, epsilon, gpuOutput.length);
    }

    private static RuntimeParityReport compareRuntimeParity(
            double[] gpuOutput,
            double[] expected,
            double epsilon,
            int points) {
        double maxAbsError = 0.0D;
        for (int i = 0; i < points; i++) {
            double actual = gpuOutput[i];
            double mirror = expected[i];
            if (runtimeSameValue(actual, mirror)) {
                continue;
            }
            double diff = runtimeAbsError(actual, mirror);
            if (diff > maxAbsError) {
                maxAbsError = diff;
            }
            if (!(diff <= epsilon)) {
                return RuntimeParityReport.failed(
                        points,
                        maxAbsError,
                        "runtime parity mismatch at index " + i
                                + ": gpu=" + actual
                                + ", cpuMirror=" + mirror
                                + ", diff=" + diff
                                + ", epsilon=" + epsilon);
            }
        }
        return RuntimeParityReport.passed(points, maxAbsError);
    }

    private static boolean runtimeSameValue(double actual, double expected) {
        return Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(expected)
                || (Double.isNaN(actual) && Double.isNaN(expected));
    }

    private static double runtimeAbsError(double actual, double expected) {
        double diff = Math.abs(actual - expected);
        return Double.isNaN(diff) ? Double.POSITIVE_INFINITY : diff;
    }

    public static void computeCpu(GpuIrPayload payload, int[] blockX, int[] blockY, int[] blockZ, double[] output) {
        computeCpu(payload, blockX, blockY, blockZ, emptyExternValues(payload), output);
    }

    public static void computeCpu(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output) {
        validate(payload, blockX, blockY, blockZ, output, externValues);
        for (int i = 0; i < output.length; i++) {
            output[i] = GpuPayloadCpuEvaluator.compute(payload, blockX[i], blockY[i], blockZ[i], externValues, i);
        }
    }

    public static GpuAttempt tryComputeGpu(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] output) {
        return tryComputeGpu(payload, blockX, blockY, blockZ, emptyExternValues(payload), output);
    }

    public static GpuAttempt tryComputeGpu(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output) {
        validate(payload, blockX, blockY, blockZ, output, externValues);
        if (output.length == 0) {
            return GpuAttempt.ok();
        }
        try {
            int scratchLength = Math.multiplyExact(output.length, payload.nodeCount());
            double[] scratch = new double[scratchLength];
            return tryComputeGpu(payload, blockX, blockY, blockZ, externValues, output, scratch);
        } catch (ArithmeticException exception) {
            return GpuAttempt.failed(exception.toString());
        }
    }

    public static GpuAttempt tryComputeGpu(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output,
            double[] scratch) {
        return tryComputeGpu(payload, blockX, blockY, blockZ, externValues, output, scratch, true);
    }

    private static GpuAttempt tryComputeGpu(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output,
            double[] scratch,
            boolean recordRuntimeLockStats) {
        return tryComputeGpu(
                payload, blockX, blockY, blockZ, externValues, output, scratch,
                recordRuntimeLockStats, recordRuntimeLockStats);
    }

    private static GpuAttempt tryComputeGpu(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output,
            double[] scratch,
            boolean recordRuntimeLockStats,
            boolean publishPreparedLauncherState) {
        validate(payload, blockX, blockY, blockZ, output, externValues);
        Objects.requireNonNull(scratch, "scratch");
        if (output.length == 0) {
            return GpuAttempt.ok();
        }
        try {
            int scratchLength = Math.multiplyExact(output.length, payload.nodeCount());
            if (scratch.length < scratchLength) {
                throw new IllegalArgumentException("Scratch buffer length " + scratch.length
                        + " is smaller than required length " + scratchLength);
            }

            Object[] kernelArgs = new Object[]{
                    blockX,
                    blockY,
                    blockZ,
                    payload.opcodes(),
                    payload.arg0(),
                    payload.arg1(),
                    payload.arg2(),
                    payload.int0(),
                    payload.int1(),
                    payload.value0(),
                    payload.value1(),
                    gpuNoisePermutations(payload),
                    gpuNoiseOctaveData(payload),
                    payload.externInputCount(),
                    gpuExternValues(payload, externValues),
                    payload.rootIndex(),
                    payload.nodeCount(),
                    scratch,
                    output
            };

            PreparedInvocationResult invocation = invokeGpuRuntime(
                    output.length, kernelArgs, recordRuntimeLockStats, publishPreparedLauncherState);
            if (!invocation.invoked()) {
                return GpuAttempt.skipped(invocation.failureReason());
            }
            return GpuAttempt.ok(invocation.cacheHit(), invocation.timings());
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return GpuAttempt.failed(cause.toString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return GpuAttempt.failed(exception.toString());
        }
    }

    public static GpuAttempt tryComputeGpuRuntimeBatch(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output,
            double[] scratch) {
        validate(payload, blockX, blockY, blockZ, output, externValues);
        Objects.requireNonNull(scratch, "scratch");
        if (output.length == 0) {
            return GpuAttempt.ok();
        }
        try {
            int scratchLength = Math.multiplyExact(output.length, payload.nodeCount());
            if (scratch.length < scratchLength) {
                throw new IllegalArgumentException("Scratch buffer length " + scratch.length
                        + " is smaller than required length " + scratchLength);
            }
            if (runtimeMicroBatchEnabled(output.length)) {
                return tryComputeGpuRuntimeMicroBatch(payload, blockX, blockY, blockZ, externValues, output, scratch);
            }
            return tryComputeGpu(payload, blockX, blockY, blockZ, externValues, output, scratch);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return GpuAttempt.failed(cause.toString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return GpuAttempt.failed(exception.toString());
        }
    }

    public static GpuAttempt tryComputeGpuRuntimeMultiPayloadBatch(
            int payloadCount,
            int pointsPerPayload,
            int maxExternInputCount,
            int scratchStride,
            int[] payloadNodeOffsets,
            int[] payloadNodeCounts,
            int[] payloadRootIndices,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            int[] opcodes,
            int[] arg0,
            int[] arg1,
            int[] arg2,
            int[] int0,
            int[] int1,
            double[] value0,
            double[] value1,
            int[] noisePermutations,
            double[] noiseOctaveData,
            double[] externValues,
            double[] output,
            double[] scratch) {
        validateMultiPayload(
                payloadCount, pointsPerPayload, maxExternInputCount, scratchStride,
                payloadNodeOffsets, payloadNodeCounts, payloadRootIndices,
                blockX, blockY, blockZ, opcodes, arg0, arg1, arg2, int0, int1,
                value0, value1, noisePermutations, noiseOctaveData, externValues, output, scratch);
        if (output.length == 0) {
            return GpuAttempt.ok();
        }
        try {
            Object[] kernelArgs = new Object[]{
                    blockX,
                    blockY,
                    blockZ,
                    payloadNodeOffsets,
                    payloadNodeCounts,
                    payloadRootIndices,
                    opcodes,
                    arg0,
                    arg1,
                    arg2,
                    int0,
                    int1,
                    value0,
                    value1,
                    nonEmptyIntArray(noisePermutations),
                    nonEmptyDoubleArray(noiseOctaveData),
                    maxExternInputCount,
                    nonEmptyDoubleArray(externValues),
                    pointsPerPayload,
                    scratchStride,
                    scratch,
                    output
            };

            PreparedInvocationResult invocation = invokeGpuRuntimeMultiPayload(output.length, kernelArgs);
            if (!invocation.invoked()) {
                return GpuAttempt.skipped(invocation.failureReason());
            }
            return GpuAttempt.ok(invocation.cacheHit(), invocation.timings());
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return GpuAttempt.failed(cause.toString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return GpuAttempt.failed(exception.toString());
        }
    }

    private static GpuAttempt tryComputeGpuRuntimeMicroBatch(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output,
            double[] scratch) throws ReflectiveOperationException {
        int pointCount = output.length;
        RuntimeMicroBatchRequest own = new RuntimeMicroBatchRequest(
                payload, blockX, blockY, blockZ, gpuExternValues(payload, externValues), output, scratch, false);

        long waitStart = System.nanoTime();
        if (!GPU_RUNTIME_LOCK.tryLock()) {
            long queueWaitNanos = runtimeMicroBatchWaitNanos();
            RuntimeMicroBatchRequest queued = new RuntimeMicroBatchRequest(
                    payload, blockX, blockY, blockZ, gpuExternValues(payload, externValues), output, scratch, true);
            RUNTIME_MICRO_BATCH_QUEUE.offer(queued);
            try {
                if (queued.await(queueWaitNanos)) {
                    return queued.result();
                }
                if (queued.accepted()) {
                    queued.awaitDone();
                    return queued.result();
                }
                if (!queued.cancel()) {
                    queued.awaitDone();
                    return queued.result();
                }
                if (tryAcquireRuntimeLockAfterQueueTimeout(queueWaitNanos)) {
                    return leadRuntimeMicroBatch(payload, pointCount, own, waitStart);
                }
            } catch (InterruptedException exception) {
                if (!queued.cancel()) {
                    queued.awaitDoneUninterruptibly();
                    return queued.result();
                }
                Thread.currentThread().interrupt();
            }
            recordRuntimeMicroBatchBusy();
            RouterPipeline.recordGpuPayloadBatchRuntimeLock(System.nanoTime() - waitStart, 0L);
            return GpuAttempt.skipped("gpu runtime busy");
        }

        return leadRuntimeMicroBatch(payload, pointCount, own, waitStart);
    }

    private static boolean tryAcquireRuntimeLockAfterQueueTimeout(long queueWaitNanos) throws InterruptedException {
        return queueWaitNanos > 0L && GPU_RUNTIME_LOCK.tryLock(queueWaitNanos, TimeUnit.NANOSECONDS);
    }

    private static GpuAttempt leadRuntimeMicroBatch(
            GpuIrPayload payload,
            int pointCount,
            RuntimeMicroBatchRequest own,
            long waitStart) throws ReflectiveOperationException {

        long waitNanos = System.nanoTime() - waitStart;
        long heldStart = System.nanoTime();
        List<RuntimeMicroBatchRequest> requests = List.of(own);
        try {
            requests = collectRuntimeMicroBatch(own);
            int requiredRequests = runtimeMicroBatchRequiredRequests(pointCount);
            if (requests.size() < requiredRequests) {
                RouterPipeline.recordGpuPayloadBatchMicroBatchSkipped(requests.size());
                recordRuntimeMicroBatchTooSmall(requests.size());
                completeSkipped(requests, requests.size() == 1
                        ? "gpu microbatch single"
                        : "gpu microbatch below min points");
                return own.result();
            }
            recordRuntimeMicroBatchLaunch(requests.size());
            GpuRuntimeCompileOptions compileOptions = openClCompileOptionsTyped();
            return invokeRuntimeMicroBatchLocked(payload, pointCount, compileOptions, requests, own);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            completeFailed(requests, exception.toString());
            throw exception;
        } finally {
            RouterPipeline.recordGpuPayloadBatchRuntimeLock(waitNanos, System.nanoTime() - heldStart);
            GPU_RUNTIME_LOCK.unlock();
        }
    }

    private static List<RuntimeMicroBatchRequest> collectRuntimeMicroBatch(RuntimeMicroBatchRequest own) {
        int maxBatch = runtimeMicroBatchMax();
        int preferredMinBatch = runtimeMicroBatchRequiredRequests(own.output.length);
        long deadline = System.nanoTime() + runtimeMicroBatchCollectNanos();
        ArrayList<RuntimeMicroBatchRequest> requests = new ArrayList<>(maxBatch);
        ArrayList<RuntimeMicroBatchRequest> deferred = new ArrayList<>();
        own.accept();
        requests.add(own);

        while (requests.size() < maxBatch) {
            RuntimeMicroBatchRequest candidate = RUNTIME_MICRO_BATCH_QUEUE.poll();
            if (candidate == null) {
                if (requests.size() >= preferredMinBatch || System.nanoTime() >= deadline) {
                    break;
                }
                Thread.onSpinWait();
                continue;
            }
            if (candidate.cancelled()) {
                continue;
            }
            if (candidate.matches(own) && candidate.accept()) {
                requests.add(candidate);
            } else {
                deferred.add(candidate);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        for (RuntimeMicroBatchRequest request : deferred) {
            if (!request.cancelled()) {
                RUNTIME_MICRO_BATCH_QUEUE.offer(request);
            }
        }
        return requests;
    }

    private static GpuAttempt invokeRuntimeMicroBatchLocked(
            GpuIrPayload payload,
            int pointCount,
            GpuRuntimeCompileOptions compileOptions,
            List<RuntimeMicroBatchRequest> requests,
            RuntimeMicroBatchRequest own) throws ReflectiveOperationException {
        int maxBatch = runtimeMicroBatchMax();
        int combinedPoints = Math.multiplyExact(pointCount, maxBatch);
        int externInputCount = payload.externInputCount();
        RuntimeMicroBatchBuffers buffers = RUNTIME_MICRO_BATCH_BUFFERS.get();
        buffers.ensure(combinedPoints, Math.multiplyExact(combinedPoints, payload.nodeCount()),
                Math.max(1, Math.multiplyExact(combinedPoints, externInputCount)));

        for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++) {
            RuntimeMicroBatchRequest request = requests.get(requestIndex);
            int pointOffset = requestIndex * pointCount;
            System.arraycopy(request.blockX, 0, buffers.blockX, pointOffset, pointCount);
            System.arraycopy(request.blockY, 0, buffers.blockY, pointOffset, pointCount);
            System.arraycopy(request.blockZ, 0, buffers.blockZ, pointOffset, pointCount);
            if (externInputCount > 0) {
                System.arraycopy(request.externValues, 0, buffers.externValues,
                        pointOffset * externInputCount, pointCount * externInputCount);
            }
        }
        fillRuntimeMicroBatchPadding(buffers, requests.size(), maxBatch, pointCount, externInputCount);

        Object[] kernelArgs = new Object[]{
                buffers.blockX,
                buffers.blockY,
                buffers.blockZ,
                payload.opcodes(),
                payload.arg0(),
                payload.arg1(),
                payload.arg2(),
                payload.int0(),
                payload.int1(),
                payload.value0(),
                payload.value1(),
                gpuNoisePermutations(payload),
                gpuNoiseOctaveData(payload),
                payload.externInputCount(),
                buffers.externValues,
                payload.rootIndex(),
                payload.nodeCount(),
                buffers.scratch,
                buffers.output
        };

        PreparedInvocationResult invocation = invokeGpuRuntimeLocked(combinedPoints, compileOptions, kernelArgs);
        RouterPipeline.recordGpuPayloadBatchMicroBatch(requests.size(), maxBatch);
        for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++) {
            RuntimeMicroBatchRequest request = requests.get(requestIndex);
            System.arraycopy(buffers.output, requestIndex * pointCount, request.output, 0, pointCount);
            GpuAttempt result = request == own
                    ? GpuAttempt.ok(invocation.cacheHit(), invocation.timings())
                    : GpuAttempt.batched();
            request.complete(result);
        }
        return own.result();
    }

    private static void fillRuntimeMicroBatchPadding(
            RuntimeMicroBatchBuffers buffers,
            int requestCount,
            int maxBatch,
            int pointCount,
            int externInputCount) {
        if (requestCount >= maxBatch) {
            return;
        }
        int firstPaddingPoint = requestCount * pointCount;
        int paddingPoints = (maxBatch - requestCount) * pointCount;
        java.util.Arrays.fill(buffers.blockX, firstPaddingPoint, firstPaddingPoint + paddingPoints, 0);
        java.util.Arrays.fill(buffers.blockY, firstPaddingPoint, firstPaddingPoint + paddingPoints, 0);
        java.util.Arrays.fill(buffers.blockZ, firstPaddingPoint, firstPaddingPoint + paddingPoints, 0);
        if (externInputCount > 0) {
            int externStart = firstPaddingPoint * externInputCount;
            int externEnd = maxBatch * pointCount * externInputCount;
            java.util.Arrays.fill(buffers.externValues, externStart, externEnd, 0.0D);
        }
    }

    private static void completeSkipped(List<RuntimeMicroBatchRequest> requests, String reason) {
        GpuAttempt skipped = GpuAttempt.skipped(reason);
        for (RuntimeMicroBatchRequest request : requests) {
            request.complete(skipped);
        }
    }

    private static void completeFailed(List<RuntimeMicroBatchRequest> requests, String reason) {
        GpuAttempt failed = GpuAttempt.failed(reason);
        for (RuntimeMicroBatchRequest request : requests) {
            request.complete(failed);
        }
    }

    private static PreflightResult ensurePreflightPassed() {
        PreflightState state = preflightState;
        if (state == PreflightState.PASSED) {
            return PreflightResult.ok();
        }
        if (state == PreflightState.FAILED) {
            return PreflightResult.failed(preflightReason);
        }

        synchronized (PREFLIGHT_LOCK) {
            if (preflightState == PreflightState.PASSED) {
                return PreflightResult.ok();
            }
            if (preflightState == PreflightState.FAILED) {
                return PreflightResult.failed(preflightReason);
            }

            preflightState = PreflightState.RUNNING;
            PreflightResult result = runPreflight();
            preflightState = result.passed() ? PreflightState.PASSED : PreflightState.FAILED;
            preflightReason = result.reason();
            return result;
        }
    }

    private static PreflightResult runPreflight() {
        GpuIrPayload payload = preflightPayload();
        int[] blockX = new int[]{4, -8, 0};
        int[] blockY = new int[]{10, 3, -64};
        int[] blockZ = new int[]{8, -12, 16};
        double[] gpu = new double[blockX.length];
        double[] scratch = new double[Math.multiplyExact(gpu.length, payload.nodeCount())];
        GpuAttempt attempt = tryComputeGpu(
                payload, blockX, blockY, blockZ, emptyExternValues(payload), gpu, scratch, false);
        if (!attempt.success()) {
            return PreflightResult.failed("preflight launch failed: " + attempt.failureReason());
        }

        double[] expected = new double[blockX.length];
        computeCpu(payload, blockX, blockY, blockZ, expected);
        for (int i = 0; i < gpu.length; i++) {
            if (Double.doubleToRawLongBits(gpu[i]) != Double.doubleToRawLongBits(expected[i])) {
                return PreflightResult.failed("preflight parity mismatch at index " + i
                        + ": gpu=" + gpu[i] + ", cpuMirror=" + expected[i]);
            }
        }
        return PreflightResult.ok();
    }

    private static GpuIrPayload preflightPayload() {
        int nodeCount = 7;
        int[] opcodes = new int[]{
                GpuIrPayload.BLOCK_X,
                GpuIrPayload.BLOCK_Y,
                GpuIrPayload.ADD,
                GpuIrPayload.BLOCK_Z,
                GpuIrPayload.CONST,
                GpuIrPayload.MUL,
                GpuIrPayload.ADD
        };
        int[] arg0 = new int[]{-1, -1, 0, -1, -1, 3, 2};
        int[] arg1 = new int[]{-1, -1, 1, -1, -1, 4, 5};
        int[] arg2 = fillInt(nodeCount, -1);
        int[] int0 = new int[nodeCount];
        int[] int1 = new int[nodeCount];
        double[] value0 = new double[nodeCount];
        double[] value1 = new double[nodeCount];
        double[] value2 = new double[nodeCount];
        double[] value3 = new double[nodeCount];
        value0[4] = 0.25D;
        return new GpuIrPayload(
                6,
                0,
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                opcodes,
                arg0,
                arg1,
                arg2,
                int0,
                int1,
                value0,
                value1,
                value2,
                value3,
                new int[0],
                new double[0]);
    }

    private static int[] fillInt(int length, int value) {
        int[] array = new int[length];
        java.util.Arrays.fill(array, value);
        return array;
    }

    private static boolean claimRuntimeParityCheck() {
        while (true) {
            int current = RUNTIME_PARITY_REMAINING.get();
            if (current <= 0) {
                return false;
            }
            if (RUNTIME_PARITY_REMAINING.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    private static void invokeGeneratedLauncherWithPerCallScope(long globalWorkSize, Object compileOptions, Object[] kernelArgs)
            throws ReflectiveOperationException {
        Class<?> invokerClass = Class.forName(INVOKER_CLASS);
        Class<?> optionsClass = Class.forName(OPTIONS_CLASS);
        Method invoke = invokerClass.getMethod(
                "invokeWithGlobalWorkSizeAndStandardBackendAndDevice",
                Class.class,
                String.class,
                long.class,
                optionsClass,
                Object[].class);
        invoke.invoke(null, GpuPayloadArithmeticKernel.class, METHOD_NAME, globalWorkSize, compileOptions, kernelArgs);
    }

    private static void invokeGeneratedLauncherWithActiveBackend(long globalWorkSize, Object compileOptions, Object[] kernelArgs)
            throws ReflectiveOperationException {
        if (directGeneratedLauncherEnabled()) {
            invokeDirectGeneratedLauncherWithActiveBackend(globalWorkSize, compileOptions, kernelArgs);
            return;
        }
        Class<?> invokerClass = Class.forName(INVOKER_CLASS);
        Class<?> optionsClass = Class.forName(OPTIONS_CLASS);
        Method invoke = invokerClass.getMethod(
                "invokeWithGlobalWorkSizeAndCompileOptions",
                Class.class,
                String.class,
                long.class,
                optionsClass,
                Object[].class);
        invoke.invoke(null, GpuPayloadArithmeticKernel.class, METHOD_NAME, globalWorkSize, compileOptions, kernelArgs);
    }

    private static void invokeDirectGeneratedLauncherWithActiveBackend(
            long globalWorkSize,
            Object compileOptions,
            Object[] kernelArgs) throws ReflectiveOperationException {
        Class<?> launcherClass = Class.forName(DIRECT_LAUNCHER_CLASS);
        Class<?> optionsClass = Class.forName(OPTIONS_CLASS);
        Method invoke = launcherClass.getMethod(
                "invokeWithGlobalWorkSizeAndCompileOptions",
                long.class,
                optionsClass,
                int[].class,
                int[].class,
                int[].class,
                int[].class,
                int[].class,
                int[].class,
                int[].class,
                int[].class,
                int[].class,
                double[].class,
                double[].class,
                int[].class,
                double[].class,
                int.class,
                double[].class,
                int.class,
                int.class,
                double[].class,
                double[].class);
        invoke.invoke(
                null,
                globalWorkSize,
                compileOptions,
                kernelArgs[0],
                kernelArgs[1],
                kernelArgs[2],
                kernelArgs[3],
                kernelArgs[4],
                kernelArgs[5],
                kernelArgs[6],
                kernelArgs[7],
                kernelArgs[8],
                kernelArgs[9],
                kernelArgs[10],
                kernelArgs[11],
                kernelArgs[12],
                kernelArgs[13],
                kernelArgs[14],
                kernelArgs[15],
                kernelArgs[16],
                kernelArgs[17],
                kernelArgs[18]);
    }

    private static PreparedInvocationResult invokeGpuRuntime(long globalWorkSize, Object[] kernelArgs)
            throws ReflectiveOperationException {
        return invokeGpuRuntime(globalWorkSize, kernelArgs, true);
    }

    private static PreparedInvocationResult invokeGpuRuntime(
            long globalWorkSize,
            Object[] kernelArgs,
            boolean recordRuntimeLockStats)
            throws ReflectiveOperationException {
        return invokeGpuRuntime(globalWorkSize, kernelArgs, recordRuntimeLockStats, recordRuntimeLockStats);
    }

    private static PreparedInvocationResult invokeGpuRuntime(
            long globalWorkSize,
            Object[] kernelArgs,
            boolean recordRuntimeLockStats,
            boolean publishPreparedLauncherState)
            throws ReflectiveOperationException {
        GpuRuntimeCompileOptions compileOptions = openClCompileOptionsTyped();
        if (booleanProperty(SERIALIZE_RUNTIME_PROPERTY, true) || persistentRuntimeScopeEnabled()) {
            long waitStart = System.nanoTime();
            boolean locked;
            if (booleanProperty(OPPORTUNISTIC_RUNTIME_LOCK_PROPERTY, true)) {
                locked = tryAcquireRuntimeLockWithBoundedWait(runtimeLockWaitNanos());
                if (!locked) {
                    recordRuntimeMicroBatchBusy();
                    if (recordRuntimeLockStats) {
                        RouterPipeline.recordGpuPayloadBatchRuntimeLock(System.nanoTime() - waitStart, 0L);
                    }
                    return PreparedInvocationResult.skipped("gpu runtime busy");
                }
            } else {
                GPU_RUNTIME_LOCK.lock();
                locked = true;
            }
            long waitNanos = System.nanoTime() - waitStart;
            long heldStart = System.nanoTime();
            try {
                PreparedInvocationResult result = invokeGpuRuntimeLocked(
                        globalWorkSize, compileOptions, kernelArgs, publishPreparedLauncherState);
                if (result.invoked()) {
                    recordRuntimeGpuLaunch();
                }
                return result;
            } finally {
                if (recordRuntimeLockStats) {
                    RouterPipeline.recordGpuPayloadBatchRuntimeLock(waitNanos, System.nanoTime() - heldStart);
                }
                if (locked) {
                    GPU_RUNTIME_LOCK.unlock();
                }
            }
        }
        return invokeGpuRuntimeLocked(globalWorkSize, compileOptions, kernelArgs, publishPreparedLauncherState);
    }

    private static boolean tryAcquireRuntimeLockWithBoundedWait(long waitNanos) {
        if (waitNanos <= 0L) {
            return GPU_RUNTIME_LOCK.tryLock();
        }
        try {
            return GPU_RUNTIME_LOCK.tryLock(waitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static PreparedInvocationResult invokeGpuRuntimeLocked(
            long globalWorkSize,
            GpuRuntimeCompileOptions compileOptions,
            Object[] kernelArgs)
            throws ReflectiveOperationException {
        return invokeGpuRuntimeLocked(globalWorkSize, compileOptions, kernelArgs, true);
    }

    private static PreparedInvocationResult invokeGpuRuntimeLocked(
            long globalWorkSize,
            GpuRuntimeCompileOptions compileOptions,
            Object[] kernelArgs,
            boolean publishPreparedLauncherState)
            throws ReflectiveOperationException {
        if (!booleanProperty(PERSISTENT_RUNTIME_SCOPE_PROPERTY, true)) {
            return invokePreparedLauncherWithPerCallScope(globalWorkSize, compileOptions, kernelArgs);
        }
        ensurePersistentRuntimeScope(compileOptions);
        PreparedLauncherUse launcherUse = preparedLauncherFor(
                globalWorkSize, compileOptions, kernelArgs, publishPreparedLauncherState);
        launcherUse.launcher().invoke(dynamicKernelArgs(kernelArgs));
        return PreparedInvocationResult.invoked(launcherUse.cacheHit(), launcherUse.launcher().lastInvocationTimings());
    }

    private static PreparedInvocationResult invokePreparedLauncherWithPerCallScope(
            long globalWorkSize,
            GpuRuntimeCompileOptions compileOptions,
            Object[] kernelArgs) {
        GpuScope scope = JavaToGpu.useOpenClSharedCache();
        try (GpuPreparedLauncher launcher = JavaToGpu.prepareWithConfigAndCompileOptions(
                GpuPayloadArithmeticKernel.class,
                METHOD_NAME,
                JavaToGpu.launch1D(globalWorkSize),
                compileOptions,
                kernelArgs)
                .withStaticArgumentNames(staticArgumentNames(kernelArgs))
                .withoutHostUploadArgumentNames("output", "scratch")
                .withoutHostReadbackArgumentNames("scratch")) {
            launcher.invoke(dynamicKernelArgs(kernelArgs));
            return PreparedInvocationResult.invoked(false, launcher.lastInvocationTimings());
        } finally {
            scope.close();
            JavaToGpu.shutdownOpenClSharedCache();
        }
    }

    private static PreparedInvocationResult invokeGpuRuntimeMultiPayload(long globalWorkSize, Object[] kernelArgs)
            throws ReflectiveOperationException {
        GpuRuntimeCompileOptions compileOptions = openClCompileOptionsTyped();
        if (booleanProperty(SERIALIZE_RUNTIME_PROPERTY, true) || persistentRuntimeScopeEnabled()) {
            long waitStart = System.nanoTime();
            boolean locked;
            if (booleanProperty(OPPORTUNISTIC_RUNTIME_LOCK_PROPERTY, true)) {
                locked = tryAcquireRuntimeLockWithBoundedWait(runtimeLockWaitNanos());
                if (!locked) {
                    recordRuntimeMicroBatchBusy();
                    RouterPipeline.recordGpuPayloadBatchRuntimeLock(System.nanoTime() - waitStart, 0L);
                    return PreparedInvocationResult.skipped("gpu runtime busy");
                }
            } else {
                GPU_RUNTIME_LOCK.lock();
                locked = true;
            }
            long waitNanos = System.nanoTime() - waitStart;
            long heldStart = System.nanoTime();
            try {
                PreparedInvocationResult result = invokeGpuRuntimeMultiPayloadLocked(
                        globalWorkSize, compileOptions, kernelArgs);
                if (result.invoked()) {
                    recordRuntimeGpuLaunch();
                }
                return result;
            } finally {
                RouterPipeline.recordGpuPayloadBatchRuntimeLock(waitNanos, System.nanoTime() - heldStart);
                if (locked) {
                    GPU_RUNTIME_LOCK.unlock();
                }
            }
        }
        return invokeGpuRuntimeMultiPayloadLocked(globalWorkSize, compileOptions, kernelArgs);
    }

    private static PreparedInvocationResult invokeGpuRuntimeMultiPayloadLocked(
            long globalWorkSize,
            GpuRuntimeCompileOptions compileOptions,
            Object[] kernelArgs) {
        if (!booleanProperty(PERSISTENT_RUNTIME_SCOPE_PROPERTY, true)) {
            return invokeMultiPreparedLauncherWithPerCallScope(globalWorkSize, compileOptions, kernelArgs);
        }
        ensurePersistentRuntimeScopeUnchecked(compileOptions);
        PreparedLauncherUse launcherUse = preparedMultiLauncherFor(globalWorkSize, compileOptions, kernelArgs);
        launcherUse.launcher().invoke(kernelArgs);
        return PreparedInvocationResult.invoked(launcherUse.cacheHit(), launcherUse.launcher().lastInvocationTimings());
    }

    private static PreparedInvocationResult invokeMultiPreparedLauncherWithPerCallScope(
            long globalWorkSize,
            GpuRuntimeCompileOptions compileOptions,
            Object[] kernelArgs) {
        GpuScope scope = JavaToGpu.useOpenClSharedCache();
        try (GpuPreparedLauncher launcher = JavaToGpu.prepareWithConfigAndCompileOptions(
                GpuPayloadArithmeticKernel.class,
                MULTI_METHOD_NAME,
                JavaToGpu.launch1D(globalWorkSize),
                compileOptions,
                kernelArgs)
                .withStaticArgumentNames(new String[0])
                .withoutHostUploadArgumentNames("output", "scratch")
                .withoutHostReadbackArgumentNames("scratch")) {
            launcher.invoke(kernelArgs);
            return PreparedInvocationResult.invoked(false, launcher.lastInvocationTimings());
        } finally {
            scope.close();
            JavaToGpu.shutdownOpenClSharedCache();
        }
    }

    private static void ensurePersistentRuntimeScopeUnchecked(GpuRuntimeCompileOptions compileOptions) {
        try {
            ensurePersistentRuntimeScope(compileOptions);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PreparedLauncherUse preparedMultiLauncherFor(
            long globalWorkSize,
            GpuRuntimeCompileOptions compileOptions,
            Object[] kernelArgs) {
        MultiPreparedLauncherKey key = MultiPreparedLauncherKey.from(globalWorkSize, kernelArgs);
        MultiPreparedLauncherCacheKey cacheKey = new MultiPreparedLauncherCacheKey(key, compileOptions);
        MultiPreparedLauncherState current = MULTI_PREPARED_LAUNCHER_CACHE.get(cacheKey);
        if (current != null) {
            return new PreparedLauncherUse(current.launcher(), true);
        }

        GpuPreparedLauncher launcher = JavaToGpu.prepareWithConfigAndCompileOptions(
                GpuPayloadArithmeticKernel.class,
                MULTI_METHOD_NAME,
                JavaToGpu.launch1D(globalWorkSize),
                compileOptions,
                kernelArgs)
                .withStaticArgumentNames(new String[0])
                .withoutHostUploadArgumentNames("output", "scratch")
                .withoutHostReadbackArgumentNames("scratch");
        MultiPreparedLauncherState state = new MultiPreparedLauncherState(key, compileOptions, launcher);
        MULTI_PREPARED_LAUNCHER_CACHE.put(cacheKey, state);
        evictMultiPreparedLaunchers(preparedLauncherCacheMax());
        return new PreparedLauncherUse(launcher, false);
    }

    private static void evictMultiPreparedLaunchers(int maxEntries) {
        int max = Math.max(1, maxEntries);
        while (MULTI_PREPARED_LAUNCHER_CACHE.size() > max) {
            Map.Entry<MultiPreparedLauncherCacheKey, MultiPreparedLauncherState> eldest =
                    MULTI_PREPARED_LAUNCHER_CACHE.entrySet().iterator().next();
            MultiPreparedLauncherState state = eldest.getValue();
            MULTI_PREPARED_LAUNCHER_CACHE.remove(eldest.getKey());
            closeMultiPreparedLauncherState(state);
        }
    }

    private static PreparedLauncherUse preparedLauncherFor(
            long globalWorkSize,
            GpuRuntimeCompileOptions compileOptions,
            Object[] kernelArgs,
            boolean publishPreparedLauncherState) {
        PreparedLauncherKey key = PreparedLauncherKey.from(globalWorkSize, kernelArgs);
        PreparedLauncherCacheKey cacheKey = new PreparedLauncherCacheKey(key, compileOptions);
        PreparedLauncherState current = PREPARED_LAUNCHER_CACHE.get(cacheKey);
        if (current != null) {
            if (publishPreparedLauncherState) {
                preparedLauncherState = current;
            }
            return new PreparedLauncherUse(current.launcher(), true);
        }

        GpuPreparedLauncher launcher = JavaToGpu.prepareWithConfigAndCompileOptions(
                GpuPayloadArithmeticKernel.class,
                METHOD_NAME,
                JavaToGpu.launch1D(globalWorkSize),
                compileOptions,
                kernelArgs)
                .withStaticArgumentNames(staticArgumentNames(kernelArgs))
                .withoutHostUploadArgumentNames("output", "scratch")
                .withoutHostReadbackArgumentNames("scratch");
        PreparedLauncherState state = new PreparedLauncherState(key, compileOptions, launcher);
        PREPARED_LAUNCHER_CACHE.put(cacheKey, state);
        if (publishPreparedLauncherState) {
            preparedLauncherState = state;
        }
        evictPreparedLaunchers(preparedLauncherCacheMax());
        return new PreparedLauncherUse(launcher, false);
    }

    private static void evictPreparedLaunchers(int maxEntries) {
        int max = Math.max(1, maxEntries);
        while (PREPARED_LAUNCHER_CACHE.size() > max) {
            Map.Entry<PreparedLauncherCacheKey, PreparedLauncherState> eldest =
                    PREPARED_LAUNCHER_CACHE.entrySet().iterator().next();
            PreparedLauncherState state = eldest.getValue();
            PREPARED_LAUNCHER_CACHE.remove(eldest.getKey());
            if (state == preparedLauncherState) {
                preparedLauncherState = null;
            }
            closePreparedLauncherState(state);
        }
    }

    private static String[] staticArgumentNames(Object[] kernelArgs) {
        if (externInputCount(kernelArgs) > 0 || hasNativeNoise(kernelArgs)) {
            return new String[0];
        }
        return new String[]{
                "opcodes",
                "arg0",
                "arg1",
                "arg2",
                "int0",
                "int1",
                "value0",
                "value1",
                "noisePermutations",
                "noiseOctaveData",
                "externInputCount",
                "externValues",
                "rootIndex",
                "nodeCount"
        };
    }

    private static Object[] dynamicKernelArgs(Object[] kernelArgs) {
        if (externInputCount(kernelArgs) == 0 && !hasNativeNoise(kernelArgs)) {
            return new Object[]{
                    kernelArgs[0],
                    kernelArgs[1],
                    kernelArgs[2],
                    kernelArgs[17],
                    kernelArgs[18]
            };
        }
        return kernelArgs;
    }

    private static boolean hasNativeNoise(Object[] kernelArgs) {
        return intArrayLength(kernelArgs, 11) > 1 || doubleArrayLength(kernelArgs, 12) > 1;
    }

    private static int externInputCount(Object[] kernelArgs) {
        return ((Number) kernelArgs[13]).intValue();
    }

    private static int intArrayLength(Object[] kernelArgs, int index) {
        return ((int[]) kernelArgs[index]).length;
    }

    private static int doubleArrayLength(Object[] kernelArgs, int index) {
        return ((double[]) kernelArgs[index]).length;
    }

    private static void ensurePersistentRuntimeScope(GpuRuntimeCompileOptions compileOptions) throws ReflectiveOperationException {
        if (persistentRuntimeScope != null) {
            return;
        }

        GpuScope scope = JavaToGpu.useOpenClSharedCache();
        persistentRuntimeScope = scope;
        persistentRuntimeBackend = scope;
    }

    private static void ensureLegacyPersistentRuntimeScope(Object compileOptions) throws ReflectiveOperationException {
        Object activeBackend = runtimeBackend();
        Object installedBackend = persistentRuntimeBackend;
        if (persistentRuntimeScope != null) {
            if (activeBackend == installedBackend) {
                return;
            }
            throw new IllegalStateException("persistent JavaToGpu backend scope was displaced by "
                    + describeBackend(activeBackend)
                    + " before invocation; skipping GPU to avoid out-of-order scope close");
        }

        if (activeBackend != runtimeDefaultBackend()) {
            // Respect an application-provided active backend. We do not own its scope, so
            // we also do not close it from resetRuntimeState().
            return;
        }

        Object scope = useStandardBackendAndDevice(compileOptions);
        persistentRuntimeScope = scope;
        persistentRuntimeBackend = installedBackend(scope);
    }

    private static Object runtimeBackend() throws ReflectiveOperationException {
        Class<?> runtimeClass = Class.forName(RUNTIME_CLASS);
        Method backend = runtimeClass.getMethod("backend");
        return backend.invoke(null);
    }

    private static Object runtimeDefaultBackend() throws ReflectiveOperationException {
        Class<?> runtimeClass = Class.forName(RUNTIME_CLASS);
        Method defaultBackend = runtimeClass.getMethod("defaultBackend");
        return defaultBackend.invoke(null);
    }

    private static Object useStandardBackendAndDevice(Object compileOptions) throws ReflectiveOperationException {
        Class<?> runtimeClass = Class.forName(RUNTIME_CLASS);
        Class<?> optionsClass = Class.forName(OPTIONS_CLASS);
        Method useStandard = runtimeClass.getMethod("useStandardBackendAndDevice", optionsClass);
        return useStandard.invoke(null, compileOptions);
    }

    private static Object installedBackend(Object scope) throws ReflectiveOperationException {
        Method installedBackend = scope.getClass().getMethod("installedBackend");
        return installedBackend.invoke(scope);
    }

    private static void closePersistentRuntimeScope() {
        Object scope = persistentRuntimeScope;
        persistentRuntimeScope = null;
        persistentRuntimeBackend = null;
        if (scope == null) {
            return;
        }
        try {
            if (scope instanceof GpuScope gpuScope) {
                gpuScope.close();
            } else {
                Method close = scope.getClass().getMethod("close");
                close.invoke(scope);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Reset must never crash the client if some other code already changed the
            // global JavaToGpu backend. The next GPU attempt will perform a fresh preflight.
        } finally {
            try {
                JavaToGpu.shutdownOpenClSharedCache();
            } catch (RuntimeException | LinkageError ignored) {
                // Shutdown is best-effort; lifecycle disable will happen on the next real launch if needed.
            }
        }
    }

    private static void closePreparedLauncher() {
        List<PreparedLauncherState> states = new ArrayList<>(PREPARED_LAUNCHER_CACHE.values());
        List<MultiPreparedLauncherState> multiStates = new ArrayList<>(MULTI_PREPARED_LAUNCHER_CACHE.values());
        PREPARED_LAUNCHER_CACHE.clear();
        MULTI_PREPARED_LAUNCHER_CACHE.clear();
        preparedLauncherState = null;
        for (PreparedLauncherState state : states) {
            closePreparedLauncherState(state);
        }
        for (MultiPreparedLauncherState state : multiStates) {
            closeMultiPreparedLauncherState(state);
        }
    }

    private static void closePreparedLauncherState(PreparedLauncherState state) {
        if (state == null) {
            return;
        }
        try {
            state.launcher().close();
        } catch (RuntimeException ignored) {
            // The runtime can rebuild a launcher on the next batch.
        }
    }

    private static void closeMultiPreparedLauncherState(MultiPreparedLauncherState state) {
        if (state == null) {
            return;
        }
        try {
            state.launcher().close();
        } catch (RuntimeException ignored) {
            // The runtime can rebuild a launcher on the next batch.
        }
    }

    private static String describeBackend(Object backend) {
        if (backend == null) {
            return "null";
        }
        return backend.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(backend));
    }

    private static Object openClCompileOptions() throws ReflectiveOperationException {
        return openClCompileOptionsTyped();
    }

    private static GpuRuntimeCompileOptions openClCompileOptionsTyped() {
        return GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL).withoutBackendDevicePreflight();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private static void validate(GpuIrPayload payload, int[] blockX, int[] blockY, int[] blockZ, double[] output) {
        validate(payload, blockX, blockY, blockZ, output, emptyExternValues(payload));
    }

    private static void validate(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] output,
            double[] externValues) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(blockX, "blockX");
        Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(blockZ, "blockZ");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(externValues, "externValues");
        if (blockX.length < output.length || blockY.length < output.length || blockZ.length < output.length) {
            throw new IllegalArgumentException("Coordinate arrays must be at least as long as output");
        }
        int requiredExternValues = Math.multiplyExact(output.length, payload.externInputCount());
        if (externValues.length < requiredExternValues) {
            throw new IllegalArgumentException("Extern input values length " + externValues.length
                    + " is smaller than required length " + requiredExternValues);
        }
        if (payload.nodeCount() <= 0) {
            throw new IllegalArgumentException("GPU payload must contain at least one node");
        }
        if (payload.rootIndex() < 0 || payload.rootIndex() >= payload.nodeCount()) {
            throw new IllegalArgumentException("GPU payload root index is out of bounds: " + payload.rootIndex());
        }
    }

    private static void validateMultiPayload(
            int payloadCount,
            int pointsPerPayload,
            int maxExternInputCount,
            int scratchStride,
            int[] payloadNodeOffsets,
            int[] payloadNodeCounts,
            int[] payloadRootIndices,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            int[] opcodes,
            int[] arg0,
            int[] arg1,
            int[] arg2,
            int[] int0,
            int[] int1,
            double[] value0,
            double[] value1,
            int[] noisePermutations,
            double[] noiseOctaveData,
            double[] externValues,
            double[] output,
            double[] scratch) {
        Objects.requireNonNull(payloadNodeOffsets, "payloadNodeOffsets");
        Objects.requireNonNull(payloadNodeCounts, "payloadNodeCounts");
        Objects.requireNonNull(payloadRootIndices, "payloadRootIndices");
        Objects.requireNonNull(blockX, "blockX");
        Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(blockZ, "blockZ");
        Objects.requireNonNull(opcodes, "opcodes");
        Objects.requireNonNull(arg0, "arg0");
        Objects.requireNonNull(arg1, "arg1");
        Objects.requireNonNull(arg2, "arg2");
        Objects.requireNonNull(int0, "int0");
        Objects.requireNonNull(int1, "int1");
        Objects.requireNonNull(value0, "value0");
        Objects.requireNonNull(value1, "value1");
        Objects.requireNonNull(noisePermutations, "noisePermutations");
        Objects.requireNonNull(noiseOctaveData, "noiseOctaveData");
        Objects.requireNonNull(externValues, "externValues");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(scratch, "scratch");
        if (payloadCount <= 0 || pointsPerPayload <= 0) {
            throw new IllegalArgumentException("payloadCount and pointsPerPayload must be positive");
        }
        if (maxExternInputCount < 0 || scratchStride <= 0) {
            throw new IllegalArgumentException("maxExternInputCount must be non-negative and scratchStride positive");
        }
        int expectedPoints = Math.multiplyExact(payloadCount, pointsPerPayload);
        if (output.length != expectedPoints) {
            throw new IllegalArgumentException("Output length " + output.length
                    + " does not match multi-payload point count " + expectedPoints);
        }
        if (blockX.length < expectedPoints || blockY.length < expectedPoints || blockZ.length < expectedPoints) {
            throw new IllegalArgumentException("Coordinate arrays must cover every multi-payload point");
        }
        int requiredExternValues = Math.max(1, Math.multiplyExact(expectedPoints, maxExternInputCount));
        if (externValues.length < requiredExternValues) {
            throw new IllegalArgumentException("Extern input values length " + externValues.length
                    + " is smaller than required length " + requiredExternValues);
        }
        int requiredScratch = Math.multiplyExact(expectedPoints, scratchStride);
        if (scratch.length < requiredScratch) {
            throw new IllegalArgumentException("Scratch buffer length " + scratch.length
                    + " is smaller than required length " + requiredScratch);
        }
        if (payloadNodeOffsets.length < payloadCount
                || payloadNodeCounts.length < payloadCount
                || payloadRootIndices.length < payloadCount) {
            throw new IllegalArgumentException("Payload metadata arrays must cover every payload");
        }
        int nodeArrayLength = opcodes.length;
        if (arg0.length < nodeArrayLength || arg1.length < nodeArrayLength || arg2.length < nodeArrayLength
                || int0.length < nodeArrayLength || int1.length < nodeArrayLength
                || value0.length < nodeArrayLength || value1.length < nodeArrayLength) {
            throw new IllegalArgumentException("Packed payload node arrays must have matching lengths");
        }
        for (int payload = 0; payload < payloadCount; payload++) {
            int nodeOffset = payloadNodeOffsets[payload];
            int nodeCount = payloadNodeCounts[payload];
            int rootIndex = payloadRootIndices[payload];
            if (nodeOffset < 0 || nodeCount <= 0 || nodeOffset + nodeCount > nodeArrayLength) {
                throw new IllegalArgumentException("Invalid packed payload node range at payload " + payload);
            }
            if (rootIndex < 0 || rootIndex >= nodeCount) {
                throw new IllegalArgumentException("Invalid root index " + rootIndex + " at payload " + payload);
            }
            if (nodeCount > scratchStride) {
                throw new IllegalArgumentException("Payload node count exceeds scratch stride at payload " + payload);
            }
        }
    }

    private static double[] emptyExternValues(GpuIrPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.hasExternInputs()) {
            throw new IllegalArgumentException("GPU payload requires extern input values");
        }
        return new double[0];
    }

    private static double[] gpuExternValues(GpuIrPayload payload, double[] externValues) {
        if (payload.hasExternInputs()) {
            return externValues;
        }
        return externValues.length == 0 ? new double[1] : externValues;
    }

    private static int[] nonEmptyIntArray(int[] values) {
        return values.length == 0 ? new int[1] : values;
    }

    private static double[] nonEmptyDoubleArray(double[] values) {
        return values.length == 0 ? new double[1] : values;
    }

    private static int[] gpuNoisePermutations(GpuIrPayload payload) {
        int[] values = payload.noisePermutations();
        return values.length == 0 ? new int[1] : values;
    }

    private static double[] gpuNoiseOctaveData(GpuIrPayload payload) {
        double[] values = payload.noiseOctaveData();
        return values.length == 0 ? new double[1] : values;
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "unknown" : reason;
    }

    private static int runtimeParityBudget() {
        return Math.max(0, intProperty(RUNTIME_PARITY_BATCHES_PROPERTY, 8));
    }

    private static int runtimeBatchBudget() {
        return Math.max(-1, intProperty(RUNTIME_BATCH_MAX_PROPERTY, 0));
    }

    public static int runtimeMinPoints() {
        return Math.max(1, intProperty(RUNTIME_MIN_POINTS_PROPERTY, 1024));
    }

    public static long runtimeLockWaitNanos() {
        return Math.max(0L, longProperty(RUNTIME_LOCK_WAIT_NANOS_PROPERTY, 0L));
    }

    private static int preparedLauncherCacheMax() {
        return Math.max(1, intProperty(PREPARED_LAUNCHER_CACHE_MAX_PROPERTY, 128));
    }

    private static boolean runtimeMicroBatchEnabled(int pointCount) {
        return pointCount > 0
                && pointCount < runtimeMinPoints()
                && booleanProperty(OPPORTUNISTIC_RUNTIME_LOCK_PROPERTY, true)
                && runtimeMicroBatchMax() > 1;
    }

    private static boolean runtimeMicroBatchCanReachMinPoints(int pointCount) {
        if (!runtimeMicroBatchEnabled(pointCount)) {
            return false;
        }
        try {
            return Math.multiplyExact(pointCount, runtimeMicroBatchMax()) >= runtimeMinPoints();
        } catch (ArithmeticException ignored) {
            return true;
        }
    }

    private static int runtimeMicroBatchRequiredRequests(int pointCount) {
        int configuredMin = runtimeMicroBatchMin();
        if (pointCount <= 0) {
            return configuredMin;
        }
        long requiredForMinPoints = ((long) runtimeMinPoints() + pointCount - 1L) / pointCount;
        long required = Math.max(configuredMin, requiredForMinPoints);
        return Math.max(1, (int) Math.min(runtimeMicroBatchMax(), required));
    }

    public static int runtimeMicroBatchMax() {
        return Math.max(1, intProperty(RUNTIME_MICRO_BATCH_MAX_PROPERTY, 8));
    }

    public static int runtimeMicroBatchMin() {
        return Math.max(1, Math.min(runtimeMicroBatchMax(), intProperty(RUNTIME_MICRO_BATCH_MIN_PROPERTY, 2)));
    }

    public static long runtimeMicroBatchCollectNanos() {
        return Math.max(0L, longProperty(RUNTIME_MICRO_BATCH_COLLECT_NANOS_PROPERTY, 1_000_000L));
    }

    public static long runtimeMicroBatchWaitNanos() {
        return Math.max(0L, longProperty(RUNTIME_MICRO_BATCH_WAIT_NANOS_PROPERTY, 100_000L));
    }

    public static int runtimeMicroBatchBackoffSingleStreak() {
        return Math.max(1, intProperty(RUNTIME_MICRO_BATCH_BACKOFF_SINGLE_STREAK_PROPERTY, 1));
    }

    public static int runtimeMicroBatchBackoffBusyStreak() {
        return Math.max(1, intProperty(RUNTIME_MICRO_BATCH_BACKOFF_BUSY_STREAK_PROPERTY, 32));
    }

    public static int runtimeMicroBatchBackoffBatches() {
        return Math.max(0, intProperty(RUNTIME_MICRO_BATCH_BACKOFF_BATCHES_PROPERTY, 1024));
    }

    private static boolean consumeRuntimeMicroBatchBackoff() {
        if (runtimeMicroBatchMax() <= 1 || runtimeMicroBatchMin() <= 1) {
            return false;
        }
        while (true) {
            int remaining = RUNTIME_MICRO_BATCH_BACKOFF_REMAINING.get();
            if (remaining <= 0) {
                return false;
            }
            if (RUNTIME_MICRO_BATCH_BACKOFF_REMAINING.compareAndSet(remaining, remaining - 1)) {
                return true;
            }
        }
    }

    private static void recordRuntimeMicroBatchTooSmall(int requests) {
        if (requests != 1) {
            RUNTIME_MICRO_BATCH_SINGLE_STREAK.set(0);
            return;
        }
        int streak = RUNTIME_MICRO_BATCH_SINGLE_STREAK.incrementAndGet();
        int threshold = runtimeMicroBatchBackoffSingleStreak();
        int backoffBatches = runtimeMicroBatchBackoffBatches();
        if (backoffBatches > 0 && streak >= threshold) {
            RUNTIME_MICRO_BATCH_SINGLE_STREAK.set(0);
            RUNTIME_MICRO_BATCH_BUSY_STREAK.set(0);
            activateRuntimeMicroBatchBackoff(streak, backoffBatches);
        }
    }

    private static void recordRuntimeMicroBatchBusy() {
        int streak = RUNTIME_MICRO_BATCH_BUSY_STREAK.incrementAndGet();
        int threshold = runtimeMicroBatchBackoffBusyStreak();
        int backoffBatches = runtimeMicroBatchBackoffBatches();
        if (backoffBatches > 0 && streak >= threshold) {
            RUNTIME_MICRO_BATCH_BUSY_STREAK.set(0);
            RUNTIME_MICRO_BATCH_SINGLE_STREAK.set(0);
            activateRuntimeMicroBatchBackoff(streak, backoffBatches);
        }
    }

    private static void recordRuntimeMicroBatchLaunch(int requests) {
        if (requests >= runtimeMicroBatchMin()) {
            RUNTIME_MICRO_BATCH_SINGLE_STREAK.set(0);
            RUNTIME_MICRO_BATCH_BUSY_STREAK.set(0);
        }
    }

    private static void recordRuntimeGpuLaunch() {
        RUNTIME_MICRO_BATCH_SINGLE_STREAK.set(0);
        RUNTIME_MICRO_BATCH_BUSY_STREAK.set(0);
    }

    private static void activateRuntimeMicroBatchBackoff(int streak, int backoffBatches) {
        RUNTIME_MICRO_BATCH_BACKOFF_REMAINING.addAndGet(backoffBatches);
        RouterPipeline.recordGpuPayloadBatchRuntimeBackoffTrigger(streak, backoffBatches);
    }

    private static boolean claimRuntimeBatch() {
        while (true) {
            int current = RUNTIME_BATCHES_REMAINING.get();
            if (current < 0) {
                return true;
            }
            if (current == 0) {
                return false;
            }
            if (RUNTIME_BATCHES_REMAINING.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    private static int intProperty(String key, int fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longProperty(String key, long fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanProperty(String key, boolean fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static double doubleProperty(String key, double fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed >= 0.0D ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private enum PreflightState {
        NOT_RUN,
        RUNNING,
        PASSED,
        FAILED
    }

    public enum Backend {
        CPU,
        GPU
    }

    public record Execution(Backend backend, String fallbackReason) {
    }

    public record PreflightResult(boolean passed, String reason) {
        public static PreflightResult ok() {
            return new PreflightResult(true, "passed");
        }

        public static PreflightResult failed(String reason) {
            return new PreflightResult(false, normalizeReason(reason));
        }
    }

    public record RuntimeParityReport(
            boolean checked,
            boolean passed,
            int pointsChecked,
            double maxAbsError,
            String failureReason) {
        public static RuntimeParityReport skipped() {
            return new RuntimeParityReport(false, true, 0, 0.0D, "skipped");
        }

        public static RuntimeParityReport passed(int pointsChecked, double maxAbsError) {
            return new RuntimeParityReport(true, true, pointsChecked, maxAbsError, "none");
        }

        public static RuntimeParityReport failed(int pointsChecked, double maxAbsError, String failureReason) {
            return new RuntimeParityReport(true, false, pointsChecked, maxAbsError, normalizeReason(failureReason));
        }
    }

    private record PreparedLauncherState(
            PreparedLauncherKey key,
            GpuRuntimeCompileOptions compileOptions,
            GpuPreparedLauncher launcher) {
        private boolean matches(PreparedLauncherKey otherKey, GpuRuntimeCompileOptions otherCompileOptions) {
            return key.equals(otherKey) && compileOptions.equals(otherCompileOptions);
        }
    }

    private record PreparedLauncherCacheKey(
            PreparedLauncherKey key,
            GpuRuntimeCompileOptions compileOptions) {
    }

    private record MultiPreparedLauncherState(
            MultiPreparedLauncherKey key,
            GpuRuntimeCompileOptions compileOptions,
            GpuPreparedLauncher launcher) {
    }

    private record MultiPreparedLauncherCacheKey(
            MultiPreparedLauncherKey key,
            GpuRuntimeCompileOptions compileOptions) {
    }

    private static final class PreparedPayloadSignature {
        private final int[] opcodes;
        private final int[] arg0;
        private final int[] arg1;
        private final int[] arg2;
        private final int[] int0;
        private final int[] int1;
        private final double[] value0;
        private final double[] value1;
        private final int[] noisePermutations;
        private final double[] noiseOctaveData;
        private final int hash;

        private PreparedPayloadSignature(
                int[] opcodes,
                int[] arg0,
                int[] arg1,
                int[] arg2,
                int[] int0,
                int[] int1,
                double[] value0,
                double[] value1,
                int[] noisePermutations,
                double[] noiseOctaveData) {
            this.opcodes = opcodes.clone();
            this.arg0 = arg0.clone();
            this.arg1 = arg1.clone();
            this.arg2 = arg2.clone();
            this.int0 = int0.clone();
            this.int1 = int1.clone();
            this.value0 = value0.clone();
            this.value1 = value1.clone();
            this.noisePermutations = noisePermutations.clone();
            this.noiseOctaveData = noiseOctaveData.clone();
            this.hash = computeHash();
        }

        private static PreparedPayloadSignature from(Object[] kernelArgs) {
            return new PreparedPayloadSignature(
                    (int[]) kernelArgs[3],
                    (int[]) kernelArgs[4],
                    (int[]) kernelArgs[5],
                    (int[]) kernelArgs[6],
                    (int[]) kernelArgs[7],
                    (int[]) kernelArgs[8],
                    (double[]) kernelArgs[9],
                    (double[]) kernelArgs[10],
                    (int[]) kernelArgs[11],
                    (double[]) kernelArgs[12]);
        }

        private int computeHash() {
            int result = Arrays.hashCode(opcodes);
            result = 31 * result + Arrays.hashCode(arg0);
            result = 31 * result + Arrays.hashCode(arg1);
            result = 31 * result + Arrays.hashCode(arg2);
            result = 31 * result + Arrays.hashCode(int0);
            result = 31 * result + Arrays.hashCode(int1);
            result = 31 * result + Arrays.hashCode(value0);
            result = 31 * result + Arrays.hashCode(value1);
            result = 31 * result + Arrays.hashCode(noisePermutations);
            result = 31 * result + Arrays.hashCode(noiseOctaveData);
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PreparedPayloadSignature signature)) {
                return false;
            }
            return Arrays.equals(opcodes, signature.opcodes)
                    && Arrays.equals(arg0, signature.arg0)
                    && Arrays.equals(arg1, signature.arg1)
                    && Arrays.equals(arg2, signature.arg2)
                    && Arrays.equals(int0, signature.int0)
                    && Arrays.equals(int1, signature.int1)
                    && Arrays.equals(value0, signature.value0)
                    && Arrays.equals(value1, signature.value1)
                    && Arrays.equals(noisePermutations, signature.noisePermutations)
                    && Arrays.equals(noiseOctaveData, signature.noiseOctaveData);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class PreparedDoubleArraySignature {
        private final double[] values;
        private final int hash;

        private PreparedDoubleArraySignature(double[] values) {
            this.values = values.clone();
            this.hash = Arrays.hashCode(this.values);
        }

        private static PreparedDoubleArraySignature from(double[] values) {
            return new PreparedDoubleArraySignature(values);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof PreparedDoubleArraySignature signature
                    && Arrays.equals(values, signature.values);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private record PreparedLauncherUse(GpuPreparedLauncher launcher, boolean cacheHit) {
    }

    private record PreparedInvocationResult(
            boolean invoked,
            boolean cacheHit,
            GpuPreparedInvocationTimings timings,
            String failureReason) {
        private static PreparedInvocationResult invoked(boolean cacheHit, GpuPreparedInvocationTimings timings) {
            return new PreparedInvocationResult(
                    true,
                    cacheHit,
                    timings == null ? GpuPreparedInvocationTimings.empty() : timings,
                    "none");
        }

        private static PreparedInvocationResult skipped(String failureReason) {
            return new PreparedInvocationResult(false, false, GpuPreparedInvocationTimings.empty(), normalizeReason(failureReason));
        }
    }

    private record MultiPreparedLauncherKey(
            long globalWorkSize,
            int blockXLength,
            int blockYLength,
            int blockZLength,
            int payloadNodeOffsetsLength,
            int payloadNodeCountsLength,
            int payloadRootIndicesLength,
            int opcodesLength,
            int arg0Length,
            int arg1Length,
            int arg2Length,
            int int0Length,
            int int1Length,
            int value0Length,
            int value1Length,
            int noisePermutationsLength,
            int noiseOctaveDataLength,
            int maxExternInputCount,
            int externValuesLength,
            int pointsPerPayload,
            int scratchStride,
            int scratchLength,
            int outputLength) {
        private static MultiPreparedLauncherKey from(long globalWorkSize, Object[] kernelArgs) {
            return new MultiPreparedLauncherKey(
                    globalWorkSize,
                    intArrayLength(kernelArgs, 0),
                    intArrayLength(kernelArgs, 1),
                    intArrayLength(kernelArgs, 2),
                    intArrayLength(kernelArgs, 3),
                    intArrayLength(kernelArgs, 4),
                    intArrayLength(kernelArgs, 5),
                    intArrayLength(kernelArgs, 6),
                    intArrayLength(kernelArgs, 7),
                    intArrayLength(kernelArgs, 8),
                    intArrayLength(kernelArgs, 9),
                    intArrayLength(kernelArgs, 10),
                    intArrayLength(kernelArgs, 11),
                    doubleArrayLength(kernelArgs, 12),
                    doubleArrayLength(kernelArgs, 13),
                    intArrayLength(kernelArgs, 14),
                    doubleArrayLength(kernelArgs, 15),
                    intValue(kernelArgs, 16),
                    doubleArrayLength(kernelArgs, 17),
                    intValue(kernelArgs, 18),
                    intValue(kernelArgs, 19),
                    doubleArrayLength(kernelArgs, 20),
                    doubleArrayLength(kernelArgs, 21));
        }

        private static int intArrayLength(Object[] kernelArgs, int index) {
            return ((int[]) kernelArgs[index]).length;
        }

        private static int doubleArrayLength(Object[] kernelArgs, int index) {
            return ((double[]) kernelArgs[index]).length;
        }

        private static int intValue(Object[] kernelArgs, int index) {
            return ((Number) kernelArgs[index]).intValue();
        }
    }

    private record PreparedLauncherKey(
            long globalWorkSize,
            int blockXLength,
            int blockYLength,
            int blockZLength,
            int opcodesLength,
            int arg0Length,
            int arg1Length,
            int arg2Length,
            int int0Length,
            int int1Length,
            int value0Length,
            int value1Length,
            int noisePermutationsLength,
            int noiseOctaveDataLength,
            PreparedPayloadSignature staticPayloadSignature,
            int externInputCount,
            int externValuesLength,
            PreparedDoubleArraySignature staticExternValuesSignature,
            int rootIndex,
            int nodeCount,
            int scratchLength,
            int outputLength) {
        private static PreparedLauncherKey from(long globalWorkSize, Object[] kernelArgs) {
            int externInputCount = intValue(kernelArgs, 13);
            boolean hasExternInputs = externInputCount > 0;
            boolean nativeNoise = intArrayLength(kernelArgs, 11) > 1 || doubleArrayLength(kernelArgs, 12) > 1;
            boolean staticPayload = !hasExternInputs && !nativeNoise;
            return new PreparedLauncherKey(
                    globalWorkSize,
                    staticPayload ? intArrayLength(kernelArgs, 0) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 1) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 2) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 3) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 4) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 5) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 6) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 7) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 8) : -1,
                    staticPayload ? doubleArrayLength(kernelArgs, 9) : -1,
                    staticPayload ? doubleArrayLength(kernelArgs, 10) : -1,
                    staticPayload ? intArrayLength(kernelArgs, 11) : -1,
                    staticPayload ? doubleArrayLength(kernelArgs, 12) : -1,
                    staticPayload ? PreparedPayloadSignature.from(kernelArgs) : null,
                    staticPayload ? externInputCount : -1,
                    staticPayload ? doubleArrayLength(kernelArgs, 14) : -1,
                    staticPayload ? PreparedDoubleArraySignature.from((double[]) kernelArgs[14]) : null,
                    staticPayload ? intValue(kernelArgs, 15) : -1,
                    staticPayload ? intValue(kernelArgs, 16) : -1,
                    staticPayload ? doubleArrayLength(kernelArgs, 17) : -1,
                    staticPayload ? doubleArrayLength(kernelArgs, 18) : -1);
        }

        private static int intArrayLength(Object[] kernelArgs, int index) {
            return ((int[]) kernelArgs[index]).length;
        }

        private static int doubleArrayLength(Object[] kernelArgs, int index) {
            return ((double[]) kernelArgs[index]).length;
        }

        private static int intValue(Object[] kernelArgs, int index) {
            return ((Number) kernelArgs[index]).intValue();
        }
    }

    public record DebugProbeResult(
            boolean success,
            String reason,
            boolean gpuEnabled,
            boolean persistentScopeEnabled,
            boolean persistentScopeActive,
            String preflightState,
            String preflightReason,
            String disabledReason,
            int points,
            double maxAbsError,
            double firstGpuValue,
            double firstCpuValue) {
    }

    public record LargeBatchProbeResult(
            boolean success,
            String reason,
            boolean gpuEnabled,
            boolean preparedLauncherEnabled,
            boolean directGeneratedLauncherEnabled,
            boolean persistentScopeEnabled,
            boolean persistentScopeActive,
            String preflightState,
            String preflightReason,
            String disabledReason,
            LargeBatchProbeSample[] samples) {
    }

    public record LargeBatchProbeSample(
            boolean success,
            String reason,
            int points,
            long gpuNanos,
            long cpuNanos,
            double maxAbsError,
            double firstGpuValue,
            double firstCpuValue,
            boolean warmSuccess,
            String warmReason,
            long warmGpuNanos,
            double warmMaxAbsError,
            double warmFirstGpuValue,
            boolean directSuccess,
            String directReason,
            long directGpuNanos,
            double directMaxAbsError,
            double directFirstGpuValue) {
    }

    public static final class BatchBuffers {
        private int[] blockX = new int[0];
        private int[] blockY = new int[0];
        private int[] blockZ = new int[0];
        private double[] scratch = new double[0];
        private double[] externValues = new double[0];
        private double[] output = new double[0];
        private double[] parityExpected = new double[0];
        private double[] parityPayloadExpected = new double[0];

        private BatchBuffers() {
        }

        private void ensure(int pointCount, int scratchLength, int externValueLength) {
            if (blockX.length < pointCount) {
                blockX = new int[pointCount];
                blockY = new int[pointCount];
                blockZ = new int[pointCount];
                parityExpected = new double[pointCount];
            }
            if (output.length != pointCount) {
                output = new double[pointCount];
            }
            if (parityExpected.length < pointCount) {
                parityExpected = new double[pointCount];
            }
            if (parityPayloadExpected.length < pointCount) {
                parityPayloadExpected = new double[pointCount];
            }
            if (scratch.length < scratchLength) {
                scratch = new double[scratchLength];
            }
            if (externValues.length < externValueLength) {
                externValues = new double[externValueLength];
            }
        }

        public int[] blockX() {
            return blockX;
        }

        public int[] blockY() {
            return blockY;
        }

        public int[] blockZ() {
            return blockZ;
        }

        public double[] scratch() {
            return scratch;
        }

        public double[] externValues() {
            return externValues;
        }

        public double[] output() {
            return output;
        }

        public double[] parityExpected() {
            return parityExpected;
        }

        public double[] parityPayloadExpected() {
            return parityPayloadExpected;
        }
    }

    private static final class RuntimeMicroBatchBuffers {
        private int[] blockX = new int[0];
        private int[] blockY = new int[0];
        private int[] blockZ = new int[0];
        private double[] scratch = new double[0];
        private double[] externValues = new double[0];
        private double[] output = new double[0];

        private void ensure(int pointCount, int scratchLength, int externValueLength) {
            if (blockX.length < pointCount) {
                blockX = new int[pointCount];
                blockY = new int[pointCount];
                blockZ = new int[pointCount];
                output = new double[pointCount];
            }
            if (output.length < pointCount) {
                output = new double[pointCount];
            }
            if (scratch.length < scratchLength) {
                scratch = new double[scratchLength];
            }
            if (externValues.length < externValueLength) {
                externValues = new double[externValueLength];
            }
        }
    }

    private static final class RuntimeMicroBatchRequest {
        private static final int PENDING = 0;
        private static final int ACCEPTED = 1;
        private static final int CANCELLED = 2;

        private final GpuIrPayload payload;
        private final int[] blockX;
        private final int[] blockY;
        private final int[] blockZ;
        private final double[] externValues;
        private final double[] output;
        private final double[] scratch;
        private final CountDownLatch done;
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private volatile GpuAttempt result;

        private RuntimeMicroBatchRequest(
                GpuIrPayload payload,
                int[] blockX,
                int[] blockY,
                int[] blockZ,
                double[] externValues,
                double[] output,
                double[] scratch,
                boolean async) {
            this.payload = payload;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.externValues = externValues;
            this.output = output;
            this.scratch = scratch;
            this.done = async ? new CountDownLatch(1) : null;
        }

        private boolean matches(RuntimeMicroBatchRequest other) {
            return this.payload == other.payload
                    && this.output.length == other.output.length
                    && this.payload.externInputCount() == other.payload.externInputCount();
        }

        private boolean accept() {
            return state.compareAndSet(PENDING, ACCEPTED) || state.get() == ACCEPTED;
        }

        private boolean accepted() {
            return state.get() == ACCEPTED;
        }

        private boolean cancel() {
            return state.compareAndSet(PENDING, CANCELLED);
        }

        private boolean cancelled() {
            return state.get() == CANCELLED;
        }

        private boolean await(long nanos) throws InterruptedException {
            if (done == null) {
                return true;
            }
            return nanos <= 0L ? done.getCount() == 0L : done.await(nanos, TimeUnit.NANOSECONDS);
        }

        private void awaitDone() throws InterruptedException {
            if (done != null) {
                done.await();
            }
        }

        private void awaitDoneUninterruptibly() {
            if (done == null) {
                return;
            }
            boolean interrupted = false;
            while (done.getCount() > 0L) {
                try {
                    done.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void complete(GpuAttempt result) {
            this.result = result;
            if (done != null) {
                done.countDown();
            }
        }

        private GpuAttempt result() {
            return result == null ? GpuAttempt.skipped("gpu microbatch not completed") : result;
        }
    }

    public record GpuAttempt(
            boolean success,
            String failureReason,
            boolean preparedLauncherCacheHit,
            GpuPreparedInvocationTimings preparedInvocationTimings,
            boolean preparedLauncherInvoked,
            boolean disablesGpu) {
        public static GpuAttempt ok() {
            return ok(false);
        }

        public static GpuAttempt ok(boolean preparedLauncherCacheHit) {
            return ok(preparedLauncherCacheHit, GpuPreparedInvocationTimings.empty());
        }

        public static GpuAttempt ok(
                boolean preparedLauncherCacheHit,
                GpuPreparedInvocationTimings preparedInvocationTimings) {
            return new GpuAttempt(
                    true,
                    "none",
                    preparedLauncherCacheHit,
                    preparedInvocationTimings == null ? GpuPreparedInvocationTimings.empty() : preparedInvocationTimings,
                    true,
                    false);
        }

        public static GpuAttempt batched() {
            return new GpuAttempt(
                    true,
                    "none",
                    false,
                    GpuPreparedInvocationTimings.empty(),
                    false,
                    false);
        }

        public static GpuAttempt skipped(String failureReason) {
            return new GpuAttempt(
                    false,
                    normalizeReason(failureReason),
                    false,
                    GpuPreparedInvocationTimings.empty(),
                    false,
                    false);
        }

        public static GpuAttempt failed(String failureReason) {
            return new GpuAttempt(
                    false,
                    normalizeReason(failureReason),
                    false,
                    GpuPreparedInvocationTimings.empty(),
                    false,
                    true);
        }
    }
}
