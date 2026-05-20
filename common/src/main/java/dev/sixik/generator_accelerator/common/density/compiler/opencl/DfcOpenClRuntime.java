package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.natives.DfcNativeBridge;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;

/**
 * Fail-soft entry point for the experimental DFC OpenCL backend.
 *
 * <p>This class deliberately keeps device probing behind explicit config checks so
 * a disabled OpenCL backend never loads LWJGL OpenCL classes on the stable CPU/JNI path.
 */
public final class DfcOpenClRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(DfcOpenClRuntime.class);
    private static final double WRAP_AXIS_FAST_LIMIT = 16777216.0D;
    private static final double COMPILED_PLAN_EPSILON = 1.0E-6D;
    private static final int OP_PUSH_CONST = 1;
    private static final int OP_PUSH_SLOT = 2;
    private static final int OP_COND_NEG_SCALE = 3;
    private static final int OP_Y_CLAMPED_GRADIENT = 4;
    private static final int OP_RANGE_CHOICE = 5;
    private static final int OP_RANGE_CHOICE_JUMP = 6;
    private static final int OP_JUMP = 7;
    private static final int OP_BLOCK_X = 16;
    private static final int OP_BLOCK_Y = 17;
    private static final int OP_BLOCK_Z = 18;
    private static final int OP_HOIST = 19;
    private static final int OP_ADD = 32;
    private static final int OP_SUB = 33;
    private static final int OP_MUL = 34;
    private static final int OP_DIV = 35;
    private static final int OP_MIN = 36;
    private static final int OP_MAX = 37;
    private static final int OP_NEG = 48;
    private static final int OP_ABS = 49;
    private static final int OP_SQUARE = 50;
    private static final int OP_SQUEEZE = 51;
    private static final int COMPILED_PLAN_CHUNK_SOURCE_COMPILE_MAX_CHARS = 16_384;
    private static final int COMPILED_PLAN_FUSED_WAVE_SOURCE_COMPILE_MAX_CHARS = 131_072;
    private static final int COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS = 196_608;
    private static final int RUNTIME_FINAL_CHUNK_MAX_SLOTS = 8;
    private static final int RUNTIME_FINAL_CHUNK_MAX_OCTAVES = 16;
    private static final int RUNTIME_FINAL_CHUNK_MAX_COMPUTED = 2;
    private static final int COMPILED_PLAN_HYBRID_CPU_FINISH_MAX_CELLS = 1;
    /*
     * The router-level finalDensity plan is much larger (~118 slots), but the real
     * NoiseChunk hot path sees it after cache/interpolator rebinding. Those wrappers
     * collapse parts of the plan into external interpolator reads; in practice the
     * embedded finalDensity candidate is still substantial (~54 slots), while tiny
     * add/cache wrappers stay below this cutoff.
     */
    private static final int RUNTIME_FINAL_MIN_SLOTS = 32;

    private static volatile Status cachedStatus = Status.disabled();
    private static volatile DfcOpenClDeviceEnumerator.Candidate selectedCandidate;
    private static DfcOpenClDeviceContext activeContext;
    private static volatile boolean slabVmDispatchBroken;
    private static volatile boolean finalDensityHybridBroken;
    private static final Map<CompiledDensityFunction, RuntimeHybridPlan> RUNTIME_HYBRID_PLANS = new WeakHashMap<>();

    private DfcOpenClRuntime() {
    }

    public static void init() {
        if (!DfcOpenClConfig.enabled()) {
            closeActiveContext();
            selectedCandidate = null;
            slabVmDispatchBroken = false;
            finalDensityHybridBroken = false;
            cachedStatus = Status.disabled();
            LOGGER.info("DFC OpenCL: disabled. Enable config enableDensityCompilerOpenCL or -Ddfc.opencl.enabled=true to probe devices.");
            return;
        }

        if (DfcOpenClConfig.probeOnInit()) {
            probe(false);
        } else {
            cachedStatus = Status.enabledUnprobed();
            LOGGER.info("DFC OpenCL: enabled, startup probe disabled. Run /dfc opencl probe to enumerate devices.");
        }
    }

    public static Status status() {
        if (!DfcOpenClConfig.enabled()) {
            return Status.disabled();
        }
        Status status = cachedStatus;
        return status.enabled() ? status : Status.enabledUnprobed();
    }

    public static synchronized Status probe(boolean force) {
        if (!DfcOpenClConfig.enabled()) {
            closeActiveContext();
            selectedCandidate = null;
            slabVmDispatchBroken = false;
            finalDensityHybridBroken = false;
            cachedStatus = Status.disabled();
            return cachedStatus;
        }
        if (force) {
            finalDensityHybridBroken = false;
        }

        Status status = cachedStatus;
        if (!force && status.enabled() && status.probed()) {
            return status;
        }

        try {
            List<DfcOpenClDeviceEnumerator.Candidate> candidates = DfcOpenClDeviceEnumerator.enumerateCandidates();
            List<DfcOpenClDeviceInfo> devices = candidates.stream()
                    .map(DfcOpenClDeviceEnumerator.Candidate::info)
                    .toList();
            DfcOpenClBuildProbe.Result buildProbe = DfcOpenClConfig.compileSmokeTestOnProbe() && !candidates.isEmpty()
                    ? DfcOpenClBuildProbe.compileFirstWorking(candidates)
                    : DfcOpenClBuildProbe.Result.skipped();
            boolean available = !devices.isEmpty() && (!buildProbe.tested() || buildProbe.passed());
            String error = buildProbe.tested() && !buildProbe.passed() ? buildProbe.error() : null;
            DfcOpenClDeviceEnumerator.Candidate nextSelected = buildProbe.candidate();
            if (nextSelected == null && available && !candidates.isEmpty()) {
                nextSelected = candidates.get(0);
            }
            if (force || !sameCandidate(selectedCandidate, nextSelected)) {
                closeActiveContext();
            }
            if (force) {
                slabVmDispatchBroken = false;
            }
            selectedCandidate = available ? nextSelected : null;
            Status result = new Status(true, true, available, devices, buildProbe.tested(), buildProbe.passed(),
                    selectedCandidate == null ? buildProbe.device() : selectedCandidate.info(),
                    buildProbe.buildLog(), error);
            cachedStatus = result;
            logProbeResult(result);
            return result;
        } catch (Throwable throwable) {
            closeActiveContext();
            selectedCandidate = null;
            slabVmDispatchBroken = true;
            Status result = new Status(true, true, false, List.of(), false, false, null, null,
                    errorMessage(throwable));
            cachedStatus = result;
            LOGGER.warn("DFC OpenCL: probe failed: {}", result.error(), throwable);
            return result;
        }
    }

    public static boolean slabVmDispatchConfigured() {
        return DfcOpenClConfig.worldgenBridgeEnabled();
    }

    public static boolean slabVmDispatchBroken() {
        return slabVmDispatchBroken;
    }

    public static boolean finalDensityHybridBroken() {
        return finalDensityHybridBroken;
    }

    public static int runtimeHybridPlanCacheSize() {
        synchronized (RUNTIME_HYBRID_PLANS) {
            return RUNTIME_HYBRID_PLANS.size();
        }
    }

    public static void clearRuntimeHybridPlanCache() {
        synchronized (RUNTIME_HYBRID_PLANS) {
            RUNTIME_HYBRID_PLANS.clear();
        }
    }

    public static boolean slabVmDispatchAvailable() {
        Status status = cachedStatus;
        return DfcOpenClConfig.worldgenBridgeEnabled()
                && !slabVmDispatchBroken
                && status.enabled()
                && status.available()
                && selectedCandidate != null
                && DfcOpenClConfig.slabVmMinElements() <= DfcOpenClConfig.currentBridgeMaxElements();
    }

    public static boolean tryFillFinalDensityHybrid(CompiledDensityFunction compiled,
                                                    double[] out,
                                                    NoiseChunk chunk) {
        DfcOpenClStats.recordHybridCall();
        if (!DfcOpenClConfig.finalDensityHybridEnabled()) {
            DfcOpenClStats.recordHybridSkippedDisabled();
            return false;
        }
        if (finalDensityHybridBroken) {
            DfcOpenClStats.recordHybridSkippedBroken();
            return false;
        }
        if (compiled == null || out == null || chunk == null) {
            DfcOpenClStats.recordHybridSkippedInvalid("null compiled/out/chunk");
            return false;
        }
        int cellWidth = chunk.cellWidth;
        int cellHeight = chunk.cellHeight;
        if (cellWidth <= 0 || cellHeight <= 0) {
            DfcOpenClStats.recordHybridSkippedInvalid("invalid cell shape " + cellWidth + "x" + cellHeight);
            return false;
        }
        int n = Math.multiplyExact(Math.multiplyExact(cellWidth, cellWidth), cellHeight);
        if (out.length < n) {
            DfcOpenClStats.recordHybridSkippedInvalid("output length " + out.length + "<" + n);
            return false;
        }
        if (!runtimeHybridCellValuesCanReachMinimum(n)) {
            DfcOpenClStats.recordHybridSkippedTooSmall("runtime hybrid cell values " + n
                    + " cannot reach minSlotValues=" + DfcOpenClConfig.finalDensityHybridMinSlotValues()
                    + " before batching; skip plan build");
            return false;
        }

        RuntimeHybridPlan runtimePlan = runtimeHybridPlan(compiled);
        if (!runtimePlan.available()) {
            DfcOpenClStats.recordHybridSkippedPlan(runtimePlan.unavailableReason());
            return false;
        }

        int slotValues = Math.multiplyExact(n, runtimePlan.scheduledSlotCount());
        if (!runtimeHybridSlotValuesMeetMinimum(slotValues)) {
            DfcOpenClStats.recordHybridSkippedTooSmall("runtime hybrid slot values " + slotValues
                    + "<" + DfcOpenClConfig.finalDensityHybridMinSlotValues()
                    + "; per-cell OpenCL dispatch is too small");
            return false;
        }

        return dispatchFinalDensityHybrid(out, chunk, cellWidth, cellHeight, n, runtimePlan, slotValues);
    }

    private static synchronized boolean dispatchFinalDensityHybrid(double[] out,
                                                                   NoiseChunk chunk,
                                                                   int cellWidth,
                                                                   int cellHeight,
                                                                   int n,
                                                                   RuntimeHybridPlan runtimePlan,
                                                                   int slotValues) {
        if (finalDensityHybridBroken) {
            DfcOpenClStats.recordHybridSkippedBroken();
            return false;
        }
        Status status = cachedStatus;
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            DfcOpenClStats.recordHybridSkippedUnavailable(
                    status.error() == null ? "no available OpenCL device" : status.error());
            return false;
        }
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            DfcOpenClDeviceContext.GeneratedNoiseKernel[] kernels =
                    new DfcOpenClDeviceContext.GeneratedNoiseKernel[runtimePlan.waveSources().length];
            for (int wave = 0; wave < runtimePlan.waveSources().length; wave++) {
                kernels[wave] = context.compileGeneratedNoiseKernelCached(runtimePlan.waveSources()[wave]);
            }

            double[] requestOut = new double[n];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    runtimeNoiseCellGridRequest(requestOut, cellWidth, cellHeight, chunk, runtimePlan.descriptor());
            double[] slotBuffer = new double[slotValues];

            DfcOpenClStats.recordHybridAttempt();
            DfcOpenClStats.recordSlabAttempt(slotValues);
            DfcOpenClStats.recordSlabSubmitted();
            DfcOpenClDeviceContext.SlabVmResult result = context.evalGeneratedNoiseKernelWavesToSlotBuffer(
                    kernels, runtimePlan.kernelWaves(), request, runtimePlan.scheduledSlotCount(), true, slotBuffer);
            DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());

            fillRuntimeHybridFinalDensity(out, chunk, request, runtimePlan, slotBuffer);
            DfcOpenClStats.recordHybridSuccess();
            return true;
        } catch (Throwable throwable) {
            DfcOpenClStats.recordHybridFailure(errorMessage(throwable));
            DfcOpenClStats.recordSlabFailure();
            finalDensityHybridBroken = true;
            closeActiveContext();
            LOGGER.warn("DFC OpenCL: finalDensity hybrid dispatch failed; disabling hybrid dispatch for this session: {}",
                    errorMessage(throwable), throwable);
            return false;
        }
    }

    public static synchronized boolean tryEvalSlabInner(byte[] bytecode, double[] constants, double[] packedSlotRows,
                                                        int slotCount, int slotRowStride,
                                                        int firstNoiseBlockX, int firstNoiseBlockZ, int blockY,
                                                        int cellWidth, int slabLayout, int columnXi, int columnZi,
                                                        int columnCellHeight, double hoistValue, double[] out, int n) {
        DfcOpenClStats.recordSlabAttempt(n);
        if (!DfcOpenClConfig.worldgenBridgeEnabled()) {
            DfcOpenClStats.recordSlabSkippedDisabled();
            return false;
        }
        if (slabVmDispatchBroken) {
            DfcOpenClStats.recordSlabSkippedBroken();
            return false;
        }
        Status status = cachedStatus;
        if (!status.enabled() || !status.available() || selectedCandidate == null) {
            DfcOpenClStats.recordSlabSkippedUnavailable();
            return false;
        }
        if (n < DfcOpenClConfig.slabVmMinElements()) {
            DfcOpenClStats.recordSlabSkippedBelowMin();
            return false;
        }
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            DfcOpenClDeviceContext.SlabVmRequest request = new DfcOpenClDeviceContext.SlabVmRequest(
                    bytecode,
                    constants,
                    packedSlotRows,
                    slotCount,
                    slotRowStride,
                    firstNoiseBlockX,
                    firstNoiseBlockZ,
                    blockY,
                    cellWidth,
                    slabLayout,
                    columnXi,
                    columnZi,
                    columnCellHeight,
                    hoistValue,
                    out,
                    n);
            DfcOpenClStats.recordSlabSubmitted();
            DfcOpenClDeviceContext.SlabVmResult result = context.evalSlabVm(request);
            DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
            return true;
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            slabVmDispatchBroken = true;
            closeActiveContext();
            LOGGER.warn("DFC OpenCL: slab VM dispatch failed; disabling OpenCL slab dispatch for this session: {}",
                    errorMessage(throwable), throwable);
            return false;
        }
    }

    public static synchronized SlabVmSelfTest slabVmSelfTest() {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmSelfTest.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmSelfTest.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        try {
            if (!slabVmDispatchAvailable()) {
                return SlabVmSelfTest.failed(status.selectedDevice(), "OpenCL slab dispatch is not available.");
            }
            double[] flatSlots = DfcOpenClSlabVmSmoke.slotRowsFlat();
            double[][] slotRows = new double[][]{
                    new double[DfcOpenClSlabVmSmoke.COUNT],
                    new double[DfcOpenClSlabVmSmoke.COUNT]
            };
            System.arraycopy(flatSlots, 0, slotRows[0], 0, DfcOpenClSlabVmSmoke.COUNT);
            System.arraycopy(flatSlots, DfcOpenClSlabVmSmoke.COUNT, slotRows[1], 0, DfcOpenClSlabVmSmoke.COUNT);

            DfcOpenClStats.Snapshot before = DfcOpenClStats.snapshot();
            double[] out = new double[DfcOpenClSlabVmSmoke.COUNT];
            long started = System.nanoTime();
            DfcNativeBridge.slabInnerEval(
                    DfcOpenClSlabVmSmoke.bytecode(),
                    DfcOpenClSlabVmSmoke.constants(),
                    slotRows,
                    100,
                    200,
                    64,
                    DfcOpenClSlabVmSmoke.CELL_WIDTH,
                    DfcNativeBridge.SLAB_LAYOUT_Y_HOIST,
                    0,
                    0,
                    0,
                    3.25D,
                    out,
                    DfcOpenClSlabVmSmoke.COUNT);
            long elapsedNanos = System.nanoTime() - started;
            DfcOpenClSlabVmSmoke.validate(out);
            DfcOpenClStats.Snapshot after = DfcOpenClStats.snapshot();
            long submittedDelta = after.slabSubmitted() - before.slabSubmitted();
            long succeededDelta = after.slabSucceeded() - before.slabSucceeded();
            if (submittedDelta <= 0L || succeededDelta <= 0L) {
                return SlabVmSelfTest.failed(status.selectedDevice(),
                        "bridge path passed via fallback, not OpenCL; check minElements/availability.");
            }
            return new SlabVmSelfTest(true, status.selectedDevice(), elapsedNanos,
                    "ok, bridgeSubmitted=" + submittedDelta + ", bridgeSucceeded=" + succeededDelta);
        } catch (Throwable throwable) {
            closeActiveContext();
            return SlabVmSelfTest.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    public static synchronized SlabVmSelfTest slabVmCoordsSelfTest(int repeats) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmSelfTest.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmSelfTest.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeRepeats = Math.max(1, repeats);
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[DfcOpenClSlabVmSmoke.COUNT * safeRepeats];
            DfcOpenClStats.recordSlabAttempt(out.length);
            DfcOpenClStats.recordSlabSubmitted();
            DfcOpenClDeviceContext.SlabVmResult result =
                    context.evalSlabVmCoords(DfcOpenClSlabVmSmoke.coordsRequest(out, safeRepeats));
            DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
            DfcOpenClSlabVmSmoke.validate(out, safeRepeats);
            return new SlabVmSelfTest(true, context.deviceInfo(), result.elapsedNanos(),
                    "ok, elements=" + out.length + ", repeats=" + safeRepeats);
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmSelfTest.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    public static synchronized SlabVmCoordBenchmark slabVmCoordsBenchmark(int repeats, int iterations, int warmups) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCoordBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCoordBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int maxRepeats = Math.max(1, DfcOpenClConfig.coordBenchMaxElements() / DfcOpenClSlabVmSmoke.COUNT);
        int safeRepeats = Math.min(Math.max(1, repeats), maxRepeats);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[DfcOpenClSlabVmSmoke.COUNT * safeRepeats];
            DfcOpenClDeviceContext.SlabVmCoordsRequest request =
                    DfcOpenClSlabVmSmoke.coordsRequest(out, safeRepeats);

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result = context.evalSlabVmCoords(request);
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                DfcOpenClSlabVmSmoke.validate(out, safeRepeats);
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }
            long totalElements = (long) out.length * safeIterations;
            return new SlabVmCoordBenchmark(
                    true,
                    context.deviceInfo(),
                    safeRepeats,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    totalNanos / safeIterations,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok");
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCoordBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    public static synchronized SlabVmCellBenchmark slabVmCellBenchmark(int cellWidth, int cellHeight, int cells,
                                                                       int iterations, int warmups) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmCoordsRequest request =
                    DfcOpenClSlabVmSmoke.cellCoordsRequest(out, safeCellWidth, safeCellHeight, safeCells);

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result = context.evalSlabVmCoords(request);
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                DfcOpenClSlabVmSmoke.validateCellCoords(out, safeCellWidth, safeCellHeight, safeCells);
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }
            long totalElements = (long) out.length * safeIterations;
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    totalNanos / safeIterations,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok");
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridBenchmark(int cellWidth, int cellHeight, int cells,
                                                                           int iterations, int warmups) {
        return slabVmCellGridBenchmark(cellWidth, cellHeight, cells, iterations, warmups, true, false);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridNoReadBenchmark(int cellWidth, int cellHeight,
                                                                                 int cells, int iterations,
                                                                                 int warmups) {
        return slabVmCellGridBenchmark(cellWidth, cellHeight, cells, iterations, warmups, false, false);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridCachedBenchmark(int cellWidth, int cellHeight,
                                                                                 int cells, int iterations,
                                                                                 int warmups) {
        return slabVmCellGridBenchmark(cellWidth, cellHeight, cells, iterations, warmups, true, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridCachedNoReadBenchmark(int cellWidth, int cellHeight,
                                                                                       int cells, int iterations,
                                                                                       int warmups) {
        return slabVmCellGridBenchmark(cellWidth, cellHeight, cells, iterations, warmups, false, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridGeneratedBenchmark(int cellWidth, int cellHeight,
                                                                                    int cells, int iterations,
                                                                                    int warmups) {
        return slabVmCellGridGeneratedBenchmark(cellWidth, cellHeight, cells, iterations, warmups, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridGeneratedNoReadBenchmark(int cellWidth,
                                                                                          int cellHeight, int cells,
                                                                                          int iterations,
                                                                                          int warmups) {
        return slabVmCellGridGeneratedBenchmark(cellWidth, cellHeight, cells, iterations, warmups, false);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridDirectBenchmark(int cellWidth, int cellHeight,
                                                                                 int cells, int iterations,
                                                                                 int warmups) {
        return slabVmCellGridDirectBenchmark(cellWidth, cellHeight, cells, iterations, warmups, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridDirectNoReadBenchmark(int cellWidth, int cellHeight,
                                                                                       int cells, int iterations,
                                                                                       int warmups) {
        return slabVmCellGridDirectBenchmark(cellWidth, cellHeight, cells, iterations, warmups, false);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridNoiseBenchmark(int cellWidth, int cellHeight,
                                                                                int cells, int iterations,
                                                                                int warmups) {
        return slabVmCellGridNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups, true,
                DfcOpenClSlabVmSmoke.NOISE_SLOT_COUNT, DfcOpenClSlabVmSmoke.NOISE_OCTAVES_PER_BRANCH);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridNoiseNoReadBenchmark(int cellWidth, int cellHeight,
                                                                                      int cells, int iterations,
                                                                                      int warmups) {
        return slabVmCellGridNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups, false,
                DfcOpenClSlabVmSmoke.NOISE_SLOT_COUNT, DfcOpenClSlabVmSmoke.NOISE_OCTAVES_PER_BRANCH);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridNoiseHeavyBenchmark(int cellWidth, int cellHeight,
                                                                                     int cells, int iterations,
                                                                                     int warmups) {
        return slabVmCellGridNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups, true,
                DfcOpenClSlabVmSmoke.NOISE_HEAVY_SLOT_COUNT,
                DfcOpenClSlabVmSmoke.NOISE_HEAVY_OCTAVES_PER_BRANCH);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridNoiseHeavyNoReadBenchmark(int cellWidth,
                                                                                           int cellHeight,
                                                                                           int cells,
                                                                                           int iterations,
                                                                                           int warmups) {
        return slabVmCellGridNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups, false,
                DfcOpenClSlabVmSmoke.NOISE_HEAVY_SLOT_COUNT,
                DfcOpenClSlabVmSmoke.NOISE_HEAVY_OCTAVES_PER_BRANCH);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseBenchmark(NoiseSpec[] specs,
                                                                                    int cellWidth, int cellHeight,
                                                                                    int cells, int iterations,
                                                                                    int warmups) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseNoReadBenchmark(NoiseSpec[] specs,
                                                                                          int cellWidth,
                                                                                          int cellHeight,
                                                                                          int cells,
                                                                                          int iterations,
                                                                                          int warmups) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups, false);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseCachedBenchmark(NoiseSpec[] specs,
                                                                                          int cellWidth,
                                                                                          int cellHeight,
                                                                                          int cells,
                                                                                          int iterations,
                                                                                          int warmups) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups, true, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseCachedNoReadBenchmark(NoiseSpec[] specs,
                                                                                                int cellWidth,
                                                                                                int cellHeight,
                                                                                                int cells,
                                                                                                int iterations,
                                                                                                int warmups) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups, false, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseBySlotBenchmark(NoiseSpec[] specs,
                                                                                          int cellWidth,
                                                                                          int cellHeight,
                                                                                          int cells,
                                                                                          int iterations,
                                                                                          int warmups) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                true, true, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseBySlotNoReadBenchmark(NoiseSpec[] specs,
                                                                                                int cellWidth,
                                                                                                int cellHeight,
                                                                                                int cells,
                                                                                                int iterations,
                                                                                                int warmups) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                false, true, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseDirectBenchmark(NoiseSpec[] specs,
                                                                                          int cellWidth,
                                                                                          int cellHeight,
                                                                                          int cells,
                                                                                          int iterations,
                                                                                          int warmups) {
        return slabVmCellGridRealNoiseDirectBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups, 2);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseDirectBenchmark(NoiseSpec[] specs,
                                                                                          int cellWidth,
                                                                                          int cellHeight,
                                                                                          int cells,
                                                                                          int iterations,
                                                                                          int warmups,
                                                                                          int usedSlots) {
        return slabVmCellGridRealNoiseDirectBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                usedSlots, true);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseDirectNoReadBenchmark(NoiseSpec[] specs,
                                                                                                int cellWidth,
                                                                                                int cellHeight,
                                                                                                int cells,
                                                                                                int iterations,
                                                                                                int warmups) {
        return slabVmCellGridRealNoiseDirectNoReadBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                2);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseDirectNoReadBenchmark(NoiseSpec[] specs,
                                                                                                int cellWidth,
                                                                                                int cellHeight,
                                                                                                int cells,
                                                                                                int iterations,
                                                                                                int warmups,
                                                                                                int usedSlots) {
        return slabVmCellGridRealNoiseDirectBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                usedSlots, false);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseSourceBenchmark(NoiseSpec[] specs,
                                                                                          int cellWidth,
                                                                                          int cellHeight,
                                                                                          int cells,
                                                                                          int iterations,
                                                                                          int warmups) {
        return slabVmCellGridRealNoiseSourceBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                true, DfcOpenClGeneratedNoiseSource.WrapMode.WRAP);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseSourceNoReadBenchmark(NoiseSpec[] specs,
                                                                                                int cellWidth,
                                                                                                int cellHeight,
                                                                                                int cells,
                                                                                                int iterations,
                                                                                                int warmups) {
        return slabVmCellGridRealNoiseSourceBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                false, DfcOpenClGeneratedNoiseSource.WrapMode.WRAP);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseSourceNoWrapNoReadBenchmark(
            NoiseSpec[] specs, int cellWidth, int cellHeight, int cells, int iterations, int warmups) {
        return slabVmCellGridRealNoiseSourceBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                false, DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridRealNoiseSourceAutoNoReadBenchmark(
            NoiseSpec[] specs, int cellWidth, int cellHeight, int cells, int iterations, int warmups) {
        return slabVmCellGridRealNoiseSourceBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                false, null);
    }

    public static synchronized SlabVmCellBenchmark slabVmCellGridCompiledPlanSourceAutoNoReadBenchmark(
            OpenClCompiledPlan plan, int cellWidth, int cellHeight, int cells, int iterations, int warmups) {
        return slabVmCellGridCompiledPlanSourceBenchmark(plan, cellWidth, cellHeight, cells, iterations, warmups,
                false, null);
    }

    public static synchronized GeneratedSourceCompileProbe compiledPlanChunkSourceCompileProbe(
            OpenClCompiledPlan plan, int startSlot, int endSlot) {
        if (plan == null || plan.specs() == null || plan.specs().length == 0) {
            return GeneratedSourceCompileProbe.failed(status().selectedDevice(), "compiled plan is null or empty");
        }
        int slotCount = plan.specs().length;
        int safeStart = Math.max(0, Math.min(startSlot, slotCount - 1));
        int safeEnd = Math.max(safeStart, Math.min(endSlot, slotCount - 1));

        ChunkDescriptorInput input = chunkDescriptorInput(plan, safeStart, safeEnd);
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    input.specs(), input.blendedSpecs(), input.inactiveSlots());
        } catch (Throwable throwable) {
            return GeneratedSourceCompileProbe.failed(status().selectedDevice(),
                    "Invalid chunk noise specs: " + errorMessage(throwable));
        }

        if (!DfcOpenClConfig.enabled()) {
            return GeneratedSourceCompileProbe.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return GeneratedSourceCompileProbe.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            boolean[] chunkExternalSlots = compiledPlanChunkExternalInputs(plan, safeStart, safeEnd);
            ComputedSlot[] chunkComputedSlots = chunkComputedSlots(plan, safeStart, safeEnd);
            int externalInputs = externalSlotCount(chunkExternalSlots, slotCount);
            DfcOpenClGeneratedNoiseSource.BuildResult source =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlanChunk(
                            descriptor, safeStart, safeEnd,
                            plan.slotCoordXExpressions(), plan.slotCoordYExpressions(), plan.slotCoordZExpressions(),
                            chunkExternalSlots, chunkComputedSlots,
                            DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
            if (source.source().length() > COMPILED_PLAN_CHUNK_SOURCE_COMPILE_MAX_CHARS) {
                return new GeneratedSourceCompileProbe(
                        false,
                        status.selectedDevice(),
                        safeStart,
                        safeEnd,
                        0L,
                        source.source().length(),
                        descriptor.totalOctaves,
                        source.coordScaleTemps(),
                        source.coordScaleRefs(),
                        "blocked: sourceChars " + source.source().length()
                                + ">" + COMPILED_PLAN_CHUNK_SOURCE_COMPILE_MAX_CHARS
                                + ", externalInputs=" + externalInputs
                                + ", split this chunk before native OpenCL build");
            }
            long started = System.nanoTime();
            try (DfcOpenClDeviceContext.GeneratedNoiseKernel ignored =
                         context.compileGeneratedNoiseKernel(source.source())) {
                long compileNanos = System.nanoTime() - started;
                return new GeneratedSourceCompileProbe(
                        true,
                        context.deviceInfo(),
                        safeStart,
                        safeEnd,
                        compileNanos,
                        source.source().length(),
                        descriptor.totalOctaves,
                        source.coordScaleTemps(),
                        source.coordScaleRefs(),
                        "ok, compiledPlanChunk=true, slots=" + safeStart + ".." + safeEnd
                                + ", externalInputs=" + externalInputs
                                + ", totalNoiseOctaves=" + descriptor.totalOctaves
                                + ", sourceChars=" + source.source().length()
                                + ", coordTemps=" + source.coordScaleTemps()
                                + ", coordTempRefs=" + source.coordScaleRefs()
                                + ", compileMs=" + formatMillis(compileNanos)
                                + ", wrapAxis=nowrap");
            }
        } catch (Throwable throwable) {
            closeActiveContext();
            return GeneratedSourceCompileProbe.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkSourceBenchmark(OpenClCompiledPlan plan,
                                                                                     int startSlot, int endSlot,
                                                                                     int cellWidth, int cellHeight,
                                                                                     int cells, int iterations,
                                                                                     int warmups) {
        if (plan == null || plan.specs() == null || plan.specs().length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "compiled plan is null or empty");
        }
        int slotCount = plan.specs().length;
        int safeStart = Math.max(0, Math.min(startSlot, slotCount - 1));
        int safeEnd = Math.max(safeStart, Math.min(endSlot, slotCount - 1));

        ChunkDescriptorInput input = chunkDescriptorInput(plan, safeStart, safeEnd);
        DfcOpenClNoiseDescriptor descriptor;
        DfcOpenClNoiseDescriptor fullDescriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    input.specs(), input.blendedSpecs(), input.inactiveSlots());
            fullDescriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid chunk noise specs: " + errorMessage(throwable));
        }

        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);
            boolean[] chunkExternalSlots = compiledPlanChunkExternalInputs(plan, safeStart, safeEnd);
            ComputedSlot[] chunkComputedSlots = chunkComputedSlots(plan, safeStart, safeEnd);
            int externalInputs = externalSlotCount(chunkExternalSlots, slotCount);
            if (externalInputs > 0) {
                return SlabVmCellBenchmark.failed(status.selectedDevice(),
                        "blocked: externalInputs=" + externalInputs
                                + " require staged producer slot buffers; use compiledfinaldensitychunkcompile "
                                + "for compile-only diagnostics");
            }
            DfcOpenClGeneratedNoiseSource.BuildResult source =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlanChunk(
                            descriptor, safeStart, safeEnd,
                            plan.slotCoordXExpressions(), plan.slotCoordYExpressions(), plan.slotCoordZExpressions(),
                            chunkExternalSlots, chunkComputedSlots,
                            DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
            if (source.source().length() > COMPILED_PLAN_CHUNK_SOURCE_COMPILE_MAX_CHARS) {
                return SlabVmCellBenchmark.failed(status.selectedDevice(),
                        "blocked: sourceChars " + source.source().length()
                                + ">" + COMPILED_PLAN_CHUNK_SOURCE_COMPILE_MAX_CHARS
                                + ", externalInputs=" + externalInputs
                                + ", split this chunk before native OpenCL build");
            }

            long prefillStarted = System.nanoTime();
            double[] originalExternalSlots = null;
            double[] chunkExternalValues = null;
            if (externalInputs > 0) {
                originalExternalSlots = externalSlotCount(plan.externalSlots(), slotCount) == 0
                        ? null
                        : fillExternalSlots(plan, request, slotCount);
                chunkExternalValues = fillChunkExternalInputs(plan, request, fullDescriptor, chunkExternalSlots,
                        originalExternalSlots, slotCount);
            }
            long prefillNanos = System.nanoTime() - prefillStarted;

            long compileStarted = System.nanoTime();
            try (DfcOpenClDeviceContext.GeneratedNoiseKernel generated =
                         context.compileGeneratedNoiseKernel(source.source())) {
                long compileNanos = System.nanoTime() - compileStarted;
                context.evalGeneratedNoiseKernel(generated, request, chunkExternalValues, true);
                validateCompiledPlanChunkGrid(out, request, fullDescriptor, safeStart, safeEnd,
                        plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(), plan.slotCoordZEvaluators(),
                        originalExternalSlots, plan.externalSlots(), plan.computedSlots(), slotCount);

                long totalNanos = 0L;
                long bestNanos = Long.MAX_VALUE;
                long worstNanos = 0L;
                int totalRuns = safeWarmups + safeIterations;
                for (int i = 0; i < totalRuns; i++) {
                    DfcOpenClStats.recordSlabAttempt(out.length);
                    DfcOpenClStats.recordSlabSubmitted();
                    DfcOpenClDeviceContext.SlabVmResult result =
                            context.evalGeneratedNoiseKernelReuseInputs(
                                    generated, request, chunkExternalValues, false);
                    DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                    if (i >= safeWarmups) {
                        long nanos = result.elapsedNanos();
                        totalNanos += nanos;
                        bestNanos = Math.min(bestNanos, nanos);
                        worstNanos = Math.max(worstNanos, nanos);
                    }
                }
                long totalElements = (long) out.length * safeIterations;
                long averageKernelNanos = totalNanos / safeIterations;
                return new SlabVmCellBenchmark(
                        true,
                        context.deviceInfo(),
                        safeCellWidth,
                        safeCellHeight,
                        safeCells,
                        safeIterations,
                        safeWarmups,
                        out.length,
                        totalElements,
                        totalNanos,
                        averageKernelNanos,
                        bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                        worstNanos,
                        "ok, compiledPlanChunkBench=true, slots=" + safeStart + ".." + safeEnd
                                + ", externalInputs=" + externalInputs
                                + ", totalNoiseOctaves=" + descriptor.totalOctaves
                                + ", sourceChars=" + source.source().length()
                                + ", coordTemps=" + source.coordScaleTemps()
                                + ", coordTempRefs=" + source.coordScaleRefs()
                                + ", compileMs=" + formatMillis(compileNanos)
                                + ", externalPrefillMs=" + formatMillis(prefillNanos)
                                + ", externalPrefillValueNs=" + formatNanosPerValue(
                                prefillNanos, (long) request.n() * Math.max(1, externalInputs))
                                + ", oneShotWithPrefillMs=" + formatMillis(prefillNanos + averageKernelNanos)
                                + ", wrapAxis=nowrap"
                                + ", cachedInputs=true"
                                + ", noRead=true");
            }
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkWavesSourceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups, false);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkWavesCompactSourceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups, true);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkWavesFusedCompactSourceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesFusedCompactSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups, false, false);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkWavesFusedCompactSourceReadBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesFusedCompactSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups, false, true);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkWavesFusedCompactSourceHybridBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesFusedCompactSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups,
                false, true, true);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedCompactSourceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesFusedCompactSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups, true, false);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedCompactSourceReadBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesFusedCompactSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups, true, true);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedCompactSourceHybridBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkWavesFusedCompactSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups,
                true, true, true);
    }

    private static SlabVmCellBenchmark compiledPlanChunkWavesFusedCompactSourceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups,
            boolean allWavesFused,
            boolean readSlotBuffer) {
        return compiledPlanChunkWavesFusedCompactSourceBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups,
                allWavesFused, readSlotBuffer, false);
    }

    private static SlabVmCellBenchmark compiledPlanChunkWavesFusedCompactSourceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups,
            boolean allWavesFused,
            boolean readSlotBuffer,
            boolean finishHybrid) {
        if (plan == null || plan.specs() == null || plan.specs().length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "compiled plan is null or empty");
        }
        if (chunkStartSlots == null || chunkEndSlots == null || chunkStartSlots.length != chunkEndSlots.length) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "invalid chunk ranges");
        }
        if (waves == null || waves.length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "no scheduled chunk waves");
        }

        int slotCount = plan.specs().length;
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid fused wave noise specs: " + errorMessage(throwable));
        }

        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        if (finishHybrid && safeCells > COMPILED_PLAN_HYBRID_CPU_FINISH_MAX_CELLS) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "blocked: hybrid CPU finish is diagnostic-only and capped at "
                            + COMPILED_PLAN_HYBRID_CPU_FINISH_MAX_CELLS
                            + " cell; use compiledfinaldensityallwavesoutputbench for the GPU final-output path");
        }
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        boolean[] scheduledChunks = scheduledChunks(waves, chunkStartSlots.length);
        int scheduledChunkCount = countTrue(scheduledChunks);
        if (scheduledChunkCount == 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "no chunks scheduled by waves");
        }
        int scheduledSlotCount = scheduledSlotCount(chunkStartSlots, chunkEndSlots, scheduledChunks);
        if (scheduledSlotCount <= 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "scheduled chunks contain no slots");
        }
        int[] slotBufferIndices = compactSlotBufferIndices(chunkStartSlots, chunkEndSlots, scheduledChunks, slotCount);
        int elementsPerIteration = Math.toIntExact(elements * scheduledSlotCount);
        long slotBufferBytes = Math.multiplyExact(
                Math.multiplyExact(elements, scheduledSlotCount), (long) Double.BYTES);

        DfcOpenClDeviceContext.GeneratedNoiseKernel[] kernels =
                new DfcOpenClDeviceContext.GeneratedNoiseKernel[allWavesFused ? 1 : waves.length];
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);

            long compileNanos = 0L;
            int totalSourceChars = 0;
            int maxSourceChars = 0;
            int kernelsCompiled = 0;
            if (allWavesFused) {
                boolean[] targetSlots = waveTargetSlots(scheduledChunks, chunkStartSlots, chunkEndSlots, slotCount);
                boolean[] allWaveExternalInputs = waveExternalInputs(plan, scheduledChunks,
                        chunkStartSlots, chunkEndSlots, targetSlots);
                ComputedSlot[] allWaveComputedSlots = waveComputedSlots(plan, targetSlots);
                DfcOpenClGeneratedNoiseSource.BuildResult source =
                        DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                                descriptor, targetSlots,
                                plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                plan.slotCoordZExpressions(),
                                allWaveExternalInputs, allWaveComputedSlots, slotBufferIndices,
                                DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                if (source.source().length() > COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                    return SlabVmCellBenchmark.failed(status.selectedDevice(),
                            "blocked: all-waves sourceChars " + source.source().length()
                                    + ">" + COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS
                                    + ", split waves before native OpenCL build");
                }
                long started = System.nanoTime();
                kernels[0] = context.compileGeneratedNoiseKernel(source.source());
                compileNanos += System.nanoTime() - started;
                totalSourceChars += source.source().length();
                maxSourceChars = Math.max(maxSourceChars, source.source().length());
                kernelsCompiled++;
            } else {
                for (int waveIndex = 0; waveIndex < waves.length; waveIndex++) {
                    boolean[] targetSlots = waveTargetSlots(waves[waveIndex], chunkStartSlots, chunkEndSlots, slotCount);
                    if (countTrue(targetSlots) == 0) {
                        continue;
                    }
                    boolean[] waveExternalInputs = waveExternalInputs(plan, waves[waveIndex],
                            chunkStartSlots, chunkEndSlots, targetSlots);
                    ComputedSlot[] waveComputedSlots = waveComputedSlots(plan, targetSlots);
                    DfcOpenClGeneratedNoiseSource.BuildResult source =
                            DfcOpenClGeneratedNoiseSource.buildCompiledPlanWaveCompactSlotBuffer(
                                    descriptor, targetSlots,
                                    plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                    plan.slotCoordZExpressions(),
                                    waveExternalInputs, waveComputedSlots, slotBufferIndices,
                                    DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                    if (source.source().length() > COMPILED_PLAN_FUSED_WAVE_SOURCE_COMPILE_MAX_CHARS) {
                        return SlabVmCellBenchmark.failed(status.selectedDevice(),
                                "blocked: wave=" + waveIndex
                                        + " sourceChars " + source.source().length()
                                        + ">" + COMPILED_PLAN_FUSED_WAVE_SOURCE_COMPILE_MAX_CHARS
                                        + ", split this wave before native OpenCL build");
                    }
                    long started = System.nanoTime();
                    kernels[waveIndex] = context.compileGeneratedNoiseKernel(source.source());
                    compileNanos += System.nanoTime() - started;
                    totalSourceChars += source.source().length();
                    maxSourceChars = Math.max(maxSourceChars, source.source().length());
                    kernelsCompiled++;
                }
            }

            boolean[][] fusedKernelWaves = identityWaves(kernels.length);
            long totalNanos = 0L;
            long totalGpuReadNanos = 0L;
            long totalCpuFinishNanos = 0L;
            long totalHybridStagedReads = 0L;
            double hybridChecksum = 0.0D;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            double[] slotBufferOut = (readSlotBuffer || finishHybrid) ? new double[elementsPerIteration] : null;
            double[] hybridOut = finishHybrid ? new double[Math.toIntExact(elements)] : null;
            boolean[] stagedSlots = finishHybrid
                    ? waveTargetSlots(scheduledChunks, chunkStartSlots, chunkEndSlots, slotCount)
                    : null;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(elementsPerIteration);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result =
                        context.evalGeneratedNoiseKernelWavesToSlotBuffer(
                                kernels, fusedKernelWaves, request, scheduledSlotCount, i == 0, slotBufferOut);
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                long cpuFinishNanos = 0L;
                HybridFinishStats finishStats = HybridFinishStats.empty();
                if (finishHybrid) {
                    long finishStarted = System.nanoTime();
                    finishStats = finishCompiledPlanHybridFinalDensity(
                            hybridOut, slotBufferOut, request, descriptor, plan,
                            slotBufferIndices, stagedSlots, slotCount);
                    cpuFinishNanos = System.nanoTime() - finishStarted;
                }
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos() + cpuFinishNanos;
                    totalNanos += nanos;
                    totalGpuReadNanos += result.elapsedNanos();
                    totalCpuFinishNanos += cpuFinishNanos;
                    totalHybridStagedReads += finishStats.stagedReads();
                    hybridChecksum += finishStats.checksum();
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }

            long totalElements = (long) elementsPerIteration * safeIterations;
            long averageKernelNanos = totalNanos / safeIterations;
            String benchFlag = finishHybrid
                    ? (allWavesFused
                    ? "compiledPlanAllWavesFusedHybridBench=true"
                    : "compiledPlanWaveFusedHybridBench=true")
                    : (allWavesFused
                    ? "compiledPlanAllWavesFusedBench=true"
                    : "compiledPlanWaveFusedBench=true");
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    elementsPerIteration,
                    totalElements,
                    totalNanos,
                    averageKernelNanos,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok, " + benchFlag
                            + ", waves=" + waves.length
                            + ", chunks=" + scheduledChunkCount + "/" + chunkStartSlots.length
                            + ", kernelsCompiled=" + kernelsCompiled
                            + ", slotsComputed=" + scheduledSlotCount + "/" + slotCount
                            + ", directBlockedChunks=" + directBlockedChunks
                            + ", stalledChunks=" + stalledChunks
                            + ", totalNoiseOctaves=" + descriptor.totalOctaves
                            + ", totalSourceChars=" + totalSourceChars
                            + ", maxSourceChars=" + maxSourceChars
                            + ", compileMs=" + formatMillis(compileNanos)
                            + ", slotBufferBytes=" + slotBufferBytes
                            + ", slotBufferSlots=" + scheduledSlotCount
                            + (finishHybrid
                            ? ", gpuReadMs=" + formatMillis(totalGpuReadNanos / safeIterations)
                            + ", cpuFinishMs=" + formatMillis(totalCpuFinishNanos / safeIterations)
                            + ", totalMs=" + formatMillis(averageKernelNanos)
                            + ", hybridStagedReads=" + totalHybridStagedReads
                            + ", hybridChecksum=" + formatDecimal(hybridChecksum)
                            : "")
                            + ", slotBufferLayout=" + (allWavesFused
                            ? "all-waves-fused-compact-slot-major"
                            : "fused-compact-slot-major")
                            + ", cachedInputs=true"
                            + (readSlotBuffer || finishHybrid ? ", readback=true" : ", noRead=true")
                            + (finishHybrid ? ", cpuFinish=true" : ""));
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        } finally {
            for (DfcOpenClDeviceContext.GeneratedNoiseKernel kernel : kernels) {
                if (kernel != null) {
                    kernel.close();
                }
            }
        }
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkWavesFusedCompactSourceCheck(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells) {
        return compiledPlanChunkWavesFusedCompactSourceCheck(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, false);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedCompactSourceCheck(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells) {
        return compiledPlanChunkWavesFusedCompactSourceCheck(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, true);
    }

    private static SlabVmCellBenchmark compiledPlanChunkWavesFusedCompactSourceCheck(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            boolean allWavesFused) {
        if (plan == null || plan.specs() == null || plan.specs().length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "compiled plan is null or empty");
        }
        if (chunkStartSlots == null || chunkEndSlots == null || chunkStartSlots.length != chunkEndSlots.length) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "invalid chunk ranges");
        }
        if (waves == null || waves.length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "no scheduled chunk waves");
        }

        int slotCount = plan.specs().length;
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid fused wave noise specs: " + errorMessage(throwable));
        }

        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        boolean[] scheduledChunks = scheduledChunks(waves, chunkStartSlots.length);
        int scheduledChunkCount = countTrue(scheduledChunks);
        if (scheduledChunkCount == 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "no chunks scheduled by waves");
        }
        int scheduledSlotCount = scheduledSlotCount(chunkStartSlots, chunkEndSlots, scheduledChunks);
        if (scheduledSlotCount <= 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "scheduled chunks contain no slots");
        }
        int[] slotBufferIndices = compactSlotBufferIndices(chunkStartSlots, chunkEndSlots, scheduledChunks, slotCount);
        boolean[] targetSlots = waveTargetSlots(scheduledChunks, chunkStartSlots, chunkEndSlots, slotCount);
        int elementsPerIteration = Math.toIntExact(elements * scheduledSlotCount);
        long slotBufferBytes = Math.multiplyExact(
                Math.multiplyExact(elements, scheduledSlotCount), (long) Double.BYTES);

        DfcOpenClDeviceContext.GeneratedNoiseKernel[] kernels =
                new DfcOpenClDeviceContext.GeneratedNoiseKernel[allWavesFused ? 1 : waves.length];
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);

            long compileNanos = 0L;
            int totalSourceChars = 0;
            int maxSourceChars = 0;
            int kernelsCompiled = 0;
            if (allWavesFused) {
                boolean[] allWaveExternalInputs = waveExternalInputs(plan, scheduledChunks,
                        chunkStartSlots, chunkEndSlots, targetSlots);
                ComputedSlot[] allWaveComputedSlots = waveComputedSlots(plan, targetSlots);
                DfcOpenClGeneratedNoiseSource.BuildResult source =
                        DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                                descriptor, targetSlots,
                                plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                plan.slotCoordZExpressions(),
                                allWaveExternalInputs, allWaveComputedSlots, slotBufferIndices,
                                DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                if (source.source().length() > COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                    return SlabVmCellBenchmark.failed(status.selectedDevice(),
                            "blocked: all-waves sourceChars " + source.source().length()
                                    + ">" + COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS
                                    + ", split waves before native OpenCL build");
                }
                long started = System.nanoTime();
                kernels[0] = context.compileGeneratedNoiseKernel(source.source());
                compileNanos += System.nanoTime() - started;
                totalSourceChars += source.source().length();
                maxSourceChars = Math.max(maxSourceChars, source.source().length());
                kernelsCompiled++;
            } else {
                for (int waveIndex = 0; waveIndex < waves.length; waveIndex++) {
                    boolean[] waveTargetSlots = waveTargetSlots(waves[waveIndex], chunkStartSlots, chunkEndSlots,
                            slotCount);
                    if (countTrue(waveTargetSlots) == 0) {
                        continue;
                    }
                    boolean[] waveExternalInputs = waveExternalInputs(plan, waves[waveIndex],
                            chunkStartSlots, chunkEndSlots, waveTargetSlots);
                    ComputedSlot[] waveComputedSlots = waveComputedSlots(plan, waveTargetSlots);
                    DfcOpenClGeneratedNoiseSource.BuildResult source =
                            DfcOpenClGeneratedNoiseSource.buildCompiledPlanWaveCompactSlotBuffer(
                                    descriptor, waveTargetSlots,
                                    plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                    plan.slotCoordZExpressions(),
                                    waveExternalInputs, waveComputedSlots, slotBufferIndices,
                                    DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                    if (source.source().length() > COMPILED_PLAN_FUSED_WAVE_SOURCE_COMPILE_MAX_CHARS) {
                        return SlabVmCellBenchmark.failed(status.selectedDevice(),
                                "blocked: wave=" + waveIndex
                                        + " sourceChars " + source.source().length()
                                        + ">" + COMPILED_PLAN_FUSED_WAVE_SOURCE_COMPILE_MAX_CHARS
                                        + ", split this wave before native OpenCL build");
                    }
                    long started = System.nanoTime();
                    kernels[waveIndex] = context.compileGeneratedNoiseKernel(source.source());
                    compileNanos += System.nanoTime() - started;
                    totalSourceChars += source.source().length();
                    maxSourceChars = Math.max(maxSourceChars, source.source().length());
                    kernelsCompiled++;
                }
            }

            double[] slotBufferOut = new double[elementsPerIteration];
            boolean[][] fusedKernelWaves = identityWaves(kernels.length);
            DfcOpenClStats.recordSlabAttempt(elementsPerIteration);
            DfcOpenClStats.recordSlabSubmitted();
            DfcOpenClDeviceContext.SlabVmResult result = context.evalGeneratedNoiseKernelWavesToSlotBuffer(
                    kernels, fusedKernelWaves, request, scheduledSlotCount, true, slotBufferOut);
            DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());

            double[] originalExternalSlotValues = fillExternalSlots(plan, request, slotCount);
            WaveSlotBufferValidation validation = validateCompiledPlanWaveSlotBuffer(
                    slotBufferOut, request, descriptor, slotBufferIndices, targetSlots,
                    plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(), plan.slotCoordZEvaluators(),
                    originalExternalSlotValues, plan.externalSlots(), plan.computedSlots(), slotCount, 257);
            HybridFinalDensityValidation hybridValidation = validateCompiledPlanHybridFinalDensity(
                    slotBufferOut, request, descriptor, plan, slotBufferIndices, targetSlots, slotCount, 257);

            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    1,
                    0,
                    elementsPerIteration,
                    elementsPerIteration,
                    result.elapsedNanos(),
                    result.elapsedNanos(),
                    result.elapsedNanos(),
                    result.elapsedNanos(),
                    "ok, " + (allWavesFused
                            ? "compiledPlanAllWavesFusedCheck=true"
                            : "compiledPlanWaveFusedCheck=true")
                            + ", compiledPlanHybridFinalDensityCheck=true"
                            + ", waves=" + waves.length
                            + ", chunks=" + scheduledChunkCount + "/" + chunkStartSlots.length
                            + ", kernelsCompiled=" + kernelsCompiled
                            + ", slotsComputed=" + scheduledSlotCount + "/" + slotCount
                            + ", directBlockedChunks=" + directBlockedChunks
                            + ", stalledChunks=" + stalledChunks
                            + ", checkedElements=" + validation.checkedElements()
                            + ", checkedSlotValues=" + validation.checkedSlots()
                            + ", maxAbsError=" + formatDecimal(validation.maxAbsError())
                            + ", hybridCheckedElements=" + hybridValidation.checkedElements()
                            + ", hybridStagedReads=" + hybridValidation.stagedReads()
                            + ", hybridMaxAbsError=" + formatDecimal(hybridValidation.maxAbsError())
                            + ", totalNoiseOctaves=" + descriptor.totalOctaves
                            + ", totalSourceChars=" + totalSourceChars
                            + ", maxSourceChars=" + maxSourceChars
                            + ", compileMs=" + formatMillis(compileNanos)
                            + ", slotBufferBytes=" + slotBufferBytes
                            + ", slotBufferSlots=" + scheduledSlotCount
                            + ", slotBufferLayout=" + (allWavesFused
                            ? "all-waves-fused-compact-slot-major"
                            : "fused-compact-slot-major")
                            + ", readback=true");
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        } finally {
            for (DfcOpenClDeviceContext.GeneratedNoiseKernel kernel : kernels) {
                if (kernel != null) {
                    kernel.close();
                }
            }
        }
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedFinalOutputCheck(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells) {
        return compiledPlanChunkAllWavesFusedFinalOutput(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, 1, 0, true, true);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedFinalOutputBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkAllWavesFusedFinalOutputBenchmark(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups, true);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedFinalOutputBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups,
            boolean readOutput) {
        return compiledPlanChunkAllWavesFusedFinalOutput(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups,
                false, readOutput, false);
    }

    public static synchronized SlabVmCellBenchmark compiledPlanChunkAllWavesFusedFinalOutputTraceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups) {
        return compiledPlanChunkAllWavesFusedFinalOutput(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups,
                false, false, true);
    }

    private static SlabVmCellBenchmark compiledPlanChunkAllWavesFusedFinalOutput(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups,
            boolean checkOnly,
            boolean readOutput) {
        return compiledPlanChunkAllWavesFusedFinalOutput(plan, chunkStartSlots, chunkEndSlots, waves,
                directBlockedChunks, stalledChunks, cellWidth, cellHeight, cells, iterations, warmups,
                checkOnly, readOutput, false);
    }

    private static SlabVmCellBenchmark compiledPlanChunkAllWavesFusedFinalOutput(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups,
            boolean checkOnly,
            boolean readOutput,
            boolean traceStages) {
        if (plan == null || plan.specs() == null || plan.specs().length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "compiled plan is null or empty");
        }
        if (chunkStartSlots == null || chunkEndSlots == null || chunkStartSlots.length != chunkEndSlots.length) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "invalid chunk ranges");
        }
        if (waves == null || waves.length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "no scheduled chunk waves");
        }

        int slotCount = plan.specs().length;
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid final output noise specs: " + errorMessage(throwable));
        }

        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = checkOnly ? 1 : Math.min(Math.max(1, iterations), 256);
        int safeWarmups = checkOnly ? 0 : Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        boolean[] scheduledChunks = scheduledChunks(waves, chunkStartSlots.length);
        int scheduledChunkCount = countTrue(scheduledChunks);
        if (scheduledChunkCount == 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "no chunks scheduled by waves");
        }
        int scheduledSlotCount = scheduledSlotCount(chunkStartSlots, chunkEndSlots, scheduledChunks);
        if (scheduledSlotCount <= 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "scheduled chunks contain no slots");
        }
        boolean[] scheduledSlots = waveTargetSlots(scheduledChunks, chunkStartSlots, chunkEndSlots, slotCount);
        boolean[] rootSlots = compiledPlanFinalOutputRootSlots(plan, slotCount);
        boolean[] finalResidualCandidateSlots = compiledPlanFinalOutputResidualGpuInputSlots(
                plan, scheduledSlots, slotCount);
        boolean[] markerExternalInputs = compiledPlanFinalOutputExternalInputs(plan, slotCount);
        boolean[] allWaveExternalInputs = waveExternalInputs(plan, scheduledChunks,
                chunkStartSlots, chunkEndSlots, scheduledSlots);
        boolean[] residualExternalInputs = unionSlots(scheduledSlots,
                unionSlots(markerExternalInputs, allWaveExternalInputs, slotCount), slotCount);
        boolean[] residualDependencyCandidateSlots = compiledPlanFinalOutputResidualDependencySlots(
                plan, finalResidualCandidateSlots, scheduledSlots, markerExternalInputs, slotCount);
        boolean[] slotBufferSlots = unionSlots(scheduledSlots,
                unionSlots(residualExternalInputs,
                        unionSlots(finalResidualCandidateSlots, residualDependencyCandidateSlots, slotCount),
                        slotCount), slotCount);
        int slotBufferSlotCount = countTrue(slotBufferSlots);
        if (slotBufferSlotCount <= 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "final output slot buffer has no slots");
        }
        int[] slotBufferIndices = compactSlotBufferIndices(slotBufferSlots, slotCount);
        ComputedSlot[] computedSlots = compiledPlanFinalOutputComputedSlots(plan, slotCount);
        int waveExternalInputSlotCount = countTrue(allWaveExternalInputs);
        long outputBytes = Math.multiplyExact(elements, (long) Double.BYTES);
        long slotBufferBytes = Math.multiplyExact(Math.multiplyExact(elements, slotBufferSlotCount),
                (long) Double.BYTES);

        List<DfcOpenClDeviceContext.GeneratedNoiseKernel> stageKernels = new ArrayList<>();
        List<DfcOpenClDeviceContext.FinalOutputStage> finalOutputStages = new ArrayList<>();
        DfcOpenClDeviceContext.GeneratedNoiseKernel finalKernel = null;
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);

            boolean[] foldedWaveTargetSlots = finalOutputWaveTargetsWithResidualNoise(
                    scheduledSlots, residualDependencyCandidateSlots, computedSlots, slotCount);
            boolean[] foldedWaveResidualNoiseSlots = residualDependencyNoiseBatchSlots(
                    residualDependencyCandidateSlots, null, computedSlots, slotCount);
            boolean[] foldedWaveExternalInputs = unionSlots(allWaveExternalInputs, markerExternalInputs, slotCount);
            ComputedSlot[] allWaveComputedSlots = waveComputedSlots(plan, foldedWaveTargetSlots);
            DfcOpenClGeneratedNoiseSource.BuildResult waveSource =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                            descriptor, foldedWaveTargetSlots,
                            plan.slotCoordXExpressions(), plan.slotCoordYExpressions(), plan.slotCoordZExpressions(),
                            foldedWaveExternalInputs, allWaveComputedSlots, slotBufferIndices,
                            DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
            if (countTrue(foldedWaveResidualNoiseSlots) > 0
                    && waveSource.source().length() > COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                foldedWaveResidualNoiseSlots = new boolean[slotCount];
                allWaveComputedSlots = waveComputedSlots(plan, scheduledSlots);
                waveSource = DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                        descriptor, scheduledSlots,
                        plan.slotCoordXExpressions(), plan.slotCoordYExpressions(), plan.slotCoordZExpressions(),
                        allWaveExternalInputs, allWaveComputedSlots, slotBufferIndices,
                        DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
            }
            if (waveSource.source().length() > COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                return SlabVmCellBenchmark.failed(status.selectedDevice(),
                        "blocked: all-waves final-output wave sourceChars " + waveSource.source().length()
                                + ">" + COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS
                                + ", split waves before native OpenCL build");
            }

            List<FinalOutputStageBuild> residualSources = new ArrayList<>();
            List<FinalOutputStageBuild> residualDependencySources = new ArrayList<>();
            boolean[] finalResidualDependencySlots = Arrays.copyOf(foldedWaveResidualNoiseSlots, slotCount);
            boolean[] finalResidualDependencyCpuSlots = new boolean[slotCount];
            boolean[] finalGpuInputSlots = new boolean[slotCount];
            int residualDependencySourceChars = 0;
            long residualDependencyRejectedSourceChars = 0L;
            int residualSourceChars = 0;
            int maxResidualSourceChars = 0;
            boolean acceptedDependency;
            long[] residualDependencyRejectedSourceCharsBySlot = new long[slotCount];
            do {
                acceptedDependency = false;
                boolean[] dependencyInputs = unionSlots(residualExternalInputs,
                        unionSlots(finalResidualDependencySlots, finalResidualDependencyCpuSlots, slotCount),
                        slotCount);
                boolean[] noiseBatchSlots = residualDependencyNoiseBatchSlots(
                        residualDependencyCandidateSlots, finalResidualDependencySlots, computedSlots, slotCount);
                int noiseBatchSlotCount = countTrue(noiseBatchSlots);
                if (noiseBatchSlotCount > 1) {
                    boolean[] stageInputs = slotsExcept(dependencyInputs, noiseBatchSlots, slotCount);
                    DfcOpenClGeneratedNoiseSource.BuildResult dependencySource =
                            DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                                    descriptor, noiseBatchSlots,
                                    plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                    plan.slotCoordZExpressions(),
                                    stageInputs, computedSlots, slotBufferIndices,
                                    DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                    int sourceChars = dependencySource.source().length();
                    if (sourceChars <= COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                        residualDependencySources.add(FinalOutputStageBuild.generatedBatch(
                                firstTrueSlot(noiseBatchSlots), noiseBatchSlotCount, dependencySource));
                        for (int slot = 0; slot < Math.min(noiseBatchSlots.length,
                                finalResidualDependencySlots.length); slot++) {
                            if (noiseBatchSlots[slot]) {
                                finalResidualDependencySlots[slot] = true;
                                finalResidualDependencyCpuSlots[slot] = false;
                            }
                        }
                        residualDependencySourceChars += sourceChars;
                        maxResidualSourceChars = Math.max(maxResidualSourceChars, sourceChars);
                        acceptedDependency = true;
                    }
                }
                int unresolvedDependencySlots = 0;
                for (int slot = 0; slot < residualDependencyCandidateSlots.length; slot++) {
                    if (!residualDependencyCandidateSlots[slot]
                            || finalResidualDependencySlots[slot]) {
                        continue;
                    }
                    unresolvedDependencySlots++;
                    boolean[] targetSlot = singleSlotMask(slot, slotCount);
                    boolean[] stageInputs = slotsExcept(dependencyInputs, slot, slotCount);
                    ComputedSlot computed = computedSlot(computedSlots, slot);
                    FinalOutputStageBuild dependencyStage;
                    if (computed != null) {
                        if (!computedSlotDependenciesStaged(computed, stageInputs, slot, slotCount)) {
                            continue;
                        }
                        dependencyStage = buildComputedSlotStage(
                                descriptor, slot, computed, stageInputs, slotBufferIndices);
                    } else {
                        DfcOpenClGeneratedNoiseSource.BuildResult dependencySource =
                                DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                                descriptor, targetSlot,
                                plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                plan.slotCoordZExpressions(),
                                stageInputs, computedSlots, slotBufferIndices,
                                DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                        dependencyStage = FinalOutputStageBuild.generated(slot, dependencySource);
                    }
                    int sourceChars = dependencyStage.sourceChars();
                    if (!dependencyStage.deviceVm()
                            && sourceChars > COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                        residualDependencyRejectedSourceCharsBySlot[slot] = sourceChars;
                        continue;
                    }
                    residualDependencySources.add(dependencyStage);
                    finalResidualDependencySlots[slot] = true;
                    finalResidualDependencyCpuSlots[slot] = false;
                    residualDependencySourceChars += sourceChars;
                    maxResidualSourceChars = Math.max(maxResidualSourceChars, sourceChars);
                    acceptedDependency = true;
                }
                if (!acceptedDependency && unresolvedDependencySlots > 0) {
                    int cpuSlot = residualDependencyCpuFallbackSlot(
                            residualDependencyCandidateSlots, finalResidualDependencySlots,
                            finalResidualDependencyCpuSlots, residualDependencyRejectedSourceCharsBySlot);
                    if (cpuSlot >= 0) {
                        finalResidualDependencyCpuSlots[cpuSlot] = true;
                        acceptedDependency = true;
                    }
                }
            } while (acceptedDependency);
            for (int slot = 0; slot < Math.min(finalResidualDependencyCpuSlots.length,
                    residualDependencyRejectedSourceCharsBySlot.length); slot++) {
                if (finalResidualDependencyCpuSlots[slot]) {
                    residualDependencyRejectedSourceChars += residualDependencyRejectedSourceCharsBySlot[slot];
                }
            }
            boolean[] residualStageInputs = unionSlots(residualExternalInputs,
                    unionSlots(finalResidualDependencySlots, finalResidualDependencyCpuSlots, slotCount),
                    slotCount);
            boolean acceptedResidual;
            do {
                acceptedResidual = false;
                boolean[] residualRootInputs = unionSlots(residualStageInputs, finalGpuInputSlots, slotCount);
                for (int slot = 0; slot < finalResidualCandidateSlots.length; slot++) {
                    if (!finalResidualCandidateSlots[slot] || finalGpuInputSlots[slot]) {
                        continue;
                    }
                    boolean[] targetSlot = singleSlotMask(slot, slotCount);
                    boolean[] stageInputs = slotsExcept(residualRootInputs, slot, slotCount);
                    ComputedSlot computed = computedSlot(computedSlots, slot);
                    FinalOutputStageBuild residualStage;
                    if (computed != null) {
                        if (!computedSlotDependenciesStaged(computed, stageInputs, slot, slotCount)) {
                            continue;
                        }
                        residualStage = buildComputedSlotStage(
                                descriptor, slot, computed, stageInputs, slotBufferIndices);
                    } else {
                        DfcOpenClGeneratedNoiseSource.BuildResult residualSource =
                                DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                                descriptor, targetSlot,
                                plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                plan.slotCoordZExpressions(),
                                stageInputs, computedSlots, slotBufferIndices,
                                DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                        residualStage = FinalOutputStageBuild.generated(slot, residualSource);
                    }
                    int sourceChars = residualStage.sourceChars();
                    if (!residualStage.deviceVm()
                            && sourceChars > COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                        continue;
                    }
                    residualSources.add(residualStage);
                    finalGpuInputSlots[slot] = true;
                    residualSourceChars += sourceChars;
                    maxResidualSourceChars = Math.max(maxResidualSourceChars, sourceChars);
                    acceptedResidual = true;
                }
            } while (acceptedResidual);
            boolean[] residualRejectedSlots = Arrays.copyOf(finalResidualCandidateSlots,
                    finalResidualCandidateSlots.length);
            long residualRejectedSourceChars = 0L;
            int residualRejectedSlotCount = 0;
            boolean[] residualRejectedInputs = unionSlots(residualStageInputs, finalGpuInputSlots, slotCount);
            for (int slot = 0; slot < residualRejectedSlots.length; slot++) {
                if (!residualRejectedSlots[slot] || finalGpuInputSlots[slot]) {
                    residualRejectedSlots[slot] = false;
                    continue;
                }
                boolean[] targetSlot = singleSlotMask(slot, slotCount);
                boolean[] stageInputs = slotsExcept(residualRejectedInputs, slot, slotCount);
                ComputedSlot computed = computedSlot(computedSlots, slot);
                if (computed != null && computedSlotDependenciesStaged(computed, stageInputs, slot, slotCount)) {
                    FinalOutputStageBuild residualStage =
                            buildComputedSlotStage(descriptor, slot, computed, stageInputs, slotBufferIndices);
                    residualRejectedSourceChars += residualStage.sourceChars();
                } else if (computed == null) {
                    DfcOpenClGeneratedNoiseSource.BuildResult residualSource =
                            DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                                    descriptor, targetSlot,
                                    plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                                    plan.slotCoordZExpressions(),
                                    stageInputs, computedSlots, slotBufferIndices,
                                    DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                    residualRejectedSourceChars += residualSource.source().length();
                } else {
                    residualRejectedSourceChars += COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS + 1L;
                }
                residualRejectedSlotCount++;
            }

            boolean[] finalCpuInputSlots = compiledPlanFinalOutputCpuInputSlots(
                    plan, scheduledSlots, finalGpuInputSlots, finalResidualDependencyCpuSlots, slotCount);
            boolean[] initialInputSlots = unionSlots(finalCpuInputSlots, allWaveExternalInputs, slotCount);
            int finalGpuInputSlotCount = countTrue(finalGpuInputSlots);
            int finalCpuInputSlotCount = countTrue(finalCpuInputSlots);
            int finalResidualDependencySlotCount = countTrue(finalResidualDependencySlots);
            int finalResidualDependencyCpuSlotCount = countTrue(finalResidualDependencyCpuSlots);
            int initialInputSlotCount = countTrue(initialInputSlots);

            DfcOpenClGeneratedNoiseSource.BuildResult finalSource =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlanFinalOutputFromSlotBuffer(
                            descriptor, rootSlots, plan.slabProgram(), plan.slabConstants(), plan.hoistExpression(),
                            plan.slotCoordXExpressions(), plan.slotCoordYExpressions(), plan.slotCoordZExpressions(),
                            slotBufferSlots, null, slotBufferIndices,
                            DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
            if (finalSource.source().length() > COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                return SlabVmCellBenchmark.failed(status.selectedDevice(),
                        "blocked: all-waves final-output sourceChars " + finalSource.source().length()
                                + ">" + COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS
                                + ", split waves before native OpenCL build");
            }
            int totalSourceChars = waveSource.source().length()
                    + residualDependencySourceChars
                    + residualSourceChars
                    + finalSource.source().length();
            int maxSourceChars = Math.max(Math.max(waveSource.source().length(), maxResidualSourceChars),
                    finalSource.source().length());

            long originalPrefillStarted = System.nanoTime();
            double[] originalExternalSlotValues = externalSlotCount(plan.externalSlots(), slotCount) == 0
                    ? null
                    : fillExternalSlots(plan, request, slotCount);
            long originalPrefillNanos = System.nanoTime() - originalPrefillStarted;

            long externalPrefillStarted = System.nanoTime();
            FinalOutputSlotBufferInputs initialInputs = fillFinalOutputSlotBufferInputs(
                    plan, request, descriptor, initialInputSlots, originalExternalSlotValues,
                    slotBufferIndices, slotBufferSlotCount, traceStages);
            double[] initialSlotBuffer = initialInputs.values();
            FinalOutputExternalPrefillTrace externalPrefillTrace = initialInputs.trace();
            long externalPrefillNanos = System.nanoTime() - externalPrefillStarted;

            long compileStarted = System.nanoTime();
            DfcOpenClDeviceContext.GeneratedNoiseKernel waveKernel =
                    context.compileGeneratedNoiseKernel(waveSource.source());
            stageKernels.add(waveKernel);
            finalOutputStages.add(DfcOpenClDeviceContext.FinalOutputStage.generated(waveKernel));
            for (FinalOutputStageBuild residualDependencySource : residualDependencySources) {
                addFinalOutputStage(context, residualDependencySource, finalOutputStages, stageKernels);
            }
            for (FinalOutputStageBuild residualSource : residualSources) {
                addFinalOutputStage(context, residualSource, finalOutputStages, stageKernels);
            }
            finalKernel = context.compileGeneratedNoiseKernel(finalSource.source());
            long compileNanos = System.nanoTime() - compileStarted;
            DfcOpenClDeviceContext.FinalOutputStage[] outputStages =
                    finalOutputStages.toArray(new DfcOpenClDeviceContext.FinalOutputStage[0]);
            int kernelsCompiled = stageKernels.size() + 1;
            int deviceVmStages = countDeviceVmStages(outputStages);
            String deviceVmStageList = describeDeviceVmStageBuilds(
                    plan, residualDependencySources, residualSources, 8);
            FinalOutputTraceStageInfo[] traceStageInfos = traceStages
                    ? finalOutputTraceStageInfos(
                    plan, waveSource.source().length(), residualDependencySources, residualSources)
                    : new FinalOutputTraceStageInfo[0];

            if (checkOnly) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult validationRun =
                        context.evalFinalOutputStagesToFinalOutput(
                                outputStages, finalKernel, request,
                                slotBufferSlotCount, true, initialSlotBuffer, true);
                DfcOpenClStats.recordSlabSuccess(validationRun.elapsedNanos());
                FinalOutputValidation validation = validateCompiledPlanFinalOutput(
                        out, request, descriptor, plan, originalExternalSlotValues, slotCount, 257);
                return new SlabVmCellBenchmark(
                        true,
                        context.deviceInfo(),
                        safeCellWidth,
                        safeCellHeight,
                        safeCells,
                        1,
                        0,
                        out.length,
                        out.length,
                        validationRun.elapsedNanos(),
                        validationRun.elapsedNanos(),
                        validationRun.elapsedNanos(),
                        validationRun.elapsedNanos(),
                        "ok, compiledPlanAllWavesFinalOutputCheck=true"
                                + ", finalOutput=true"
                                + ", waves=" + waves.length
                                + ", chunks=" + scheduledChunkCount + "/" + chunkStartSlots.length
                                + ", kernelsCompiled=" + kernelsCompiled
                                + ", slotsComputed=" + scheduledSlotCount + "/" + slotCount
                                + ", directBlockedChunks=" + directBlockedChunks
                                + ", stalledChunks=" + stalledChunks
                                + ", externalInputSlots=" + initialInputSlotCount
                                + ", finalCpuInputSlots=" + finalCpuInputSlotCount
                                + ", finalGpuInputSlots=" + finalGpuInputSlotCount
                                + ", waveExternalInputSlots=" + waveExternalInputSlotCount
                                + ", finalCpuSlotList="
                                + describeFinalOutputInputSlots(plan, finalCpuInputSlots, 12)
                                + ", finalGpuSlotList="
                                + describeFinalOutputInputSlots(plan, finalGpuInputSlots, 12)
                                + ", markerSlotList="
                                + describeFinalOutputInputSlots(plan, markerExternalInputs, 12)
                                + ", residualDependencySlots=" + finalResidualDependencySlotCount
                                + ", residualDependencySlotList="
                                + describeFinalOutputInputSlots(plan, finalResidualDependencySlots, 12)
                                + ", residualDependencyCpuSlots=" + finalResidualDependencyCpuSlotCount
                                + ", residualDependencyCpuSlotList="
                                + describeFinalOutputInputSlots(plan, finalResidualDependencyCpuSlots, 12)
                                + ", residualDependencyKernels=" + residualDependencySources.size()
                                + ", deviceVmStages=" + deviceVmStages
                                + ", deviceVmStageList=" + deviceVmStageList
                                + ", residualDependencySourceChars=" + residualDependencySourceChars
                                + ", residualDependencyRejectedSourceChars=" + residualDependencyRejectedSourceChars
                                + ", residualGpuKernels=" + residualSources.size()
                                + ", residualRejectedSlots=" + residualRejectedSlotCount
                                + ", residualRejectedSlotList="
                                + describeFinalOutputInputSlots(plan, residualRejectedSlots, 12)
                                + ", residualRejectedSourceChars=" + residualRejectedSourceChars
                                + ", residualSourceChars=" + residualSourceChars
                                + ", maxResidualSourceChars=" + maxResidualSourceChars
                                + ", checkedElements=" + validation.checkedElements()
                                + ", maxAbsError=" + formatDecimal(validation.maxAbsError())
                                + ", totalNoiseOctaves=" + descriptor.totalOctaves
                                + ", totalSourceChars=" + totalSourceChars
                                + ", maxSourceChars=" + maxSourceChars
                                + ", compileMs=" + formatMillis(compileNanos)
                                + ", originalExternPrefillMs=" + formatMillis(originalPrefillNanos)
                                + ", externalPrefillMs=" + formatMillis(externalPrefillNanos)
                                + ", slotBufferBytes=" + slotBufferBytes
                                + ", slotBufferSlots=" + slotBufferSlotCount
                                + ", outputBytes=" + outputBytes
                                + ", readback=true");
            }

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int validationRuns = readOutput ? 0 : 1;
            int measuredStart = validationRuns + safeWarmups;
            int totalRuns = measuredStart + safeIterations;
            long[] traceStageNanos = traceStages ? new long[outputStages.length] : new long[0];
            long traceFinalKernelNanos = 0L;
            long traceReadbackNanos = 0L;
            FinalOutputValidation validation = null;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                boolean runReadOutput = readOutput || i == 0;
                long elapsedNanos;
                if (traceStages && i >= measuredStart) {
                    DfcOpenClDeviceContext.FinalOutputTraceResult trace =
                            context.evalFinalOutputStagesToFinalOutputTrace(
                                    outputStages, finalKernel, request,
                                    slotBufferSlotCount, false, initialSlotBuffer, runReadOutput);
                    elapsedNanos = trace.elapsedNanos();
                    long[] stageNanos = trace.stageNanos();
                    for (int stage = 0; stage < Math.min(traceStageNanos.length, stageNanos.length); stage++) {
                        traceStageNanos[stage] += stageNanos[stage];
                    }
                    traceFinalKernelNanos += trace.finalKernelNanos();
                    traceReadbackNanos += trace.readbackNanos();
                } else {
                    DfcOpenClDeviceContext.SlabVmResult result = i == 0
                            ? context.evalFinalOutputStagesToFinalOutput(
                            outputStages, finalKernel, request,
                            slotBufferSlotCount, true, initialSlotBuffer, runReadOutput)
                            : context.evalFinalOutputStagesToFinalOutput(
                            outputStages, finalKernel, request,
                            slotBufferSlotCount, false, initialSlotBuffer, runReadOutput);
                    elapsedNanos = result.elapsedNanos();
                }
                DfcOpenClStats.recordSlabSuccess(elapsedNanos);
                if (i == 0) {
                    validation = validateCompiledPlanFinalOutput(
                            out, request, descriptor, plan, originalExternalSlotValues, slotCount, 257);
                }
                if (i >= measuredStart) {
                    totalNanos += elapsedNanos;
                    bestNanos = Math.min(bestNanos, elapsedNanos);
                    worstNanos = Math.max(worstNanos, elapsedNanos);
                }
            }

            long totalElements = (long) out.length * safeIterations;
            long averageKernelNanos = totalNanos / safeIterations;
            if (validation == null) {
                validation = new FinalOutputValidation(0, 0.0D);
            }
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    averageKernelNanos,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok, compiledPlanAllWavesFinalOutputBench=true"
                            + ", finalOutput=true"
                            + ", waves=" + waves.length
                            + ", chunks=" + scheduledChunkCount + "/" + chunkStartSlots.length
                            + ", kernelsCompiled=" + kernelsCompiled
                            + ", slotsComputed=" + scheduledSlotCount + "/" + slotCount
                            + ", directBlockedChunks=" + directBlockedChunks
                            + ", stalledChunks=" + stalledChunks
                            + ", externalInputSlots=" + initialInputSlotCount
                            + ", finalCpuInputSlots=" + finalCpuInputSlotCount
                            + ", finalGpuInputSlots=" + finalGpuInputSlotCount
                            + ", waveExternalInputSlots=" + waveExternalInputSlotCount
                            + ", finalCpuSlotList="
                            + describeFinalOutputInputSlots(plan, finalCpuInputSlots, 12)
                            + ", finalGpuSlotList="
                            + describeFinalOutputInputSlots(plan, finalGpuInputSlots, 12)
                            + ", markerSlotList="
                            + describeFinalOutputInputSlots(plan, markerExternalInputs, 12)
                            + ", residualDependencySlots=" + finalResidualDependencySlotCount
                            + ", residualDependencySlotList="
                            + describeFinalOutputInputSlots(plan, finalResidualDependencySlots, 12)
                            + ", residualDependencyCpuSlots=" + finalResidualDependencyCpuSlotCount
                            + ", residualDependencyCpuSlotList="
                            + describeFinalOutputInputSlots(plan, finalResidualDependencyCpuSlots, 12)
                            + ", residualDependencyKernels=" + residualDependencySources.size()
                            + ", deviceVmStages=" + deviceVmStages
                            + ", deviceVmStageList=" + deviceVmStageList
                            + ", residualDependencySourceChars=" + residualDependencySourceChars
                            + ", residualDependencyRejectedSourceChars=" + residualDependencyRejectedSourceChars
                            + ", residualGpuKernels=" + residualSources.size()
                            + ", residualRejectedSlots=" + residualRejectedSlotCount
                            + ", residualRejectedSlotList="
                            + describeFinalOutputInputSlots(plan, residualRejectedSlots, 12)
                            + ", residualRejectedSourceChars=" + residualRejectedSourceChars
                            + ", residualSourceChars=" + residualSourceChars
                            + ", maxResidualSourceChars=" + maxResidualSourceChars
                            + ", validationCheckedElements=" + validation.checkedElements()
                            + ", validationMaxAbsError=" + formatDecimal(validation.maxAbsError())
                            + ", totalNoiseOctaves=" + descriptor.totalOctaves
                            + ", totalSourceChars=" + totalSourceChars
                            + ", maxSourceChars=" + maxSourceChars
                            + ", compileMs=" + formatMillis(compileNanos)
                            + ", originalExternPrefillMs=" + formatMillis(originalPrefillNanos)
                            + ", externalPrefillMs=" + formatMillis(externalPrefillNanos)
                            + ", externalPrefillValueNs=" + formatNanosPerValue(
                            externalPrefillNanos, (long) request.n() * Math.max(1, initialInputSlotCount))
                            + (traceStages
                            ? ", externalPrefillTrace=" + describeFinalOutputExternalPrefillTrace(
                            plan, initialInputSlots, externalPrefillTrace, 8)
                            : "")
                            + ", oneShotWithPrefillMs=" + formatMillis(externalPrefillNanos + averageKernelNanos)
                            + ", slotBufferBytes=" + slotBufferBytes
                            + ", slotBufferSlots=" + slotBufferSlotCount
                            + ", outputBytes=" + outputBytes
                            + ", cachedInputs=true"
                            + (traceStages ? ", traceStages=true" : "")
                            + (traceStages
                            ? ", stageTrace=" + describeFinalOutputStageTraceTimes(
                            traceStageInfos, traceStageNanos, traceFinalKernelNanos,
                            traceReadbackNanos, safeIterations)
                            : "")
                            + (readOutput ? ", readback=true" : ", readback=false, validationReadback=true"));
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        } finally {
            for (DfcOpenClDeviceContext.FinalOutputStage stage : finalOutputStages) {
                if (stage != null) {
                    stage.close();
                }
            }
            for (DfcOpenClDeviceContext.GeneratedNoiseKernel kernel : stageKernels) {
                if (kernel != null) {
                    kernel.close();
                }
            }
            if (finalKernel != null) {
                finalKernel.close();
            }
        }
    }
    private static SlabVmCellBenchmark compiledPlanChunkWavesSourceBenchmark(
            OpenClCompiledPlan plan,
            int[] chunkStartSlots,
            int[] chunkEndSlots,
            boolean[][] waves,
            int directBlockedChunks,
            int stalledChunks,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups,
            boolean compactSlotBuffer) {
        if (plan == null || plan.specs() == null || plan.specs().length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "compiled plan is null or empty");
        }
        if (chunkStartSlots == null || chunkEndSlots == null || chunkStartSlots.length != chunkEndSlots.length) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "invalid chunk ranges");
        }
        if (waves == null || waves.length == 0) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "no scheduled chunk waves");
        }

        int slotCount = plan.specs().length;
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid wave noise specs: " + errorMessage(throwable));
        }

        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        boolean[] scheduledChunks = scheduledChunks(waves, chunkStartSlots.length);
        int scheduledChunkCount = countTrue(scheduledChunks);
        if (scheduledChunkCount == 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "no chunks scheduled by waves");
        }
        int scheduledSlotCount = scheduledSlotCount(chunkStartSlots, chunkEndSlots, scheduledChunks);
        if (scheduledSlotCount <= 0) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(), "scheduled chunks contain no slots");
        }
        int elementsPerIteration = Math.toIntExact(elements * scheduledSlotCount);
        int[] slotBufferIndices = compactSlotBuffer
                ? compactSlotBufferIndices(chunkStartSlots, chunkEndSlots, scheduledChunks, slotCount)
                : null;
        int slotBufferSlotCount = compactSlotBuffer ? scheduledSlotCount : slotCount;
        long slotBufferBytes = Math.multiplyExact(
                Math.multiplyExact(elements, slotBufferSlotCount), (long) Double.BYTES);

        DfcOpenClDeviceContext.GeneratedNoiseKernel[] kernels =
                new DfcOpenClDeviceContext.GeneratedNoiseKernel[chunkStartSlots.length];
        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);

            long compileNanos = 0L;
            int totalSourceChars = 0;
            int maxSourceChars = 0;
            int kernelsCompiled = 0;
            for (int chunk = 0; chunk < scheduledChunks.length; chunk++) {
                if (!scheduledChunks[chunk]) {
                    continue;
                }
                int safeStart = Math.max(0, Math.min(chunkStartSlots[chunk], slotCount - 1));
                int safeEnd = Math.max(safeStart, Math.min(chunkEndSlots[chunk], slotCount - 1));
                boolean[] chunkExternalSlots = compiledPlanChunkExternalInputs(plan, safeStart, safeEnd);
                ComputedSlot[] chunkComputedSlots = chunkComputedSlots(plan, safeStart, safeEnd);
                DfcOpenClGeneratedNoiseSource.BuildResult source = compactSlotBuffer
                        ? DfcOpenClGeneratedNoiseSource.buildCompiledPlanChunkCompactSlotBuffer(
                        descriptor, safeStart, safeEnd,
                        plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                        plan.slotCoordZExpressions(),
                        chunkExternalSlots, chunkComputedSlots, slotBufferIndices,
                        DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP)
                        : DfcOpenClGeneratedNoiseSource.buildCompiledPlanChunkSlotBuffer(
                        descriptor, safeStart, safeEnd,
                        plan.slotCoordXExpressions(), plan.slotCoordYExpressions(),
                        plan.slotCoordZExpressions(),
                        chunkExternalSlots, chunkComputedSlots,
                        DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
                if (source.source().length() > COMPILED_PLAN_CHUNK_SOURCE_COMPILE_MAX_CHARS) {
                    return SlabVmCellBenchmark.failed(status.selectedDevice(),
                            "blocked: chunk=" + chunk
                                    + " sourceChars " + source.source().length()
                                    + ">" + COMPILED_PLAN_CHUNK_SOURCE_COMPILE_MAX_CHARS
                                    + ", split this chunk before native OpenCL build");
                }
                long started = System.nanoTime();
                kernels[chunk] = context.compileGeneratedNoiseKernel(source.source());
                compileNanos += System.nanoTime() - started;
                totalSourceChars += source.source().length();
                maxSourceChars = Math.max(maxSourceChars, source.source().length());
                kernelsCompiled++;
            }

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(elementsPerIteration);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result =
                        context.evalGeneratedNoiseKernelWavesToSlotBuffer(
                                kernels, waves, request, slotBufferSlotCount, i == 0);
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }

            long totalElements = (long) elementsPerIteration * safeIterations;
            long averageKernelNanos = totalNanos / safeIterations;
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    elementsPerIteration,
                    totalElements,
                    totalNanos,
                    averageKernelNanos,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok, compiledPlanWaveBench=true"
                            + ", waves=" + waves.length
                            + ", chunks=" + scheduledChunkCount + "/" + chunkStartSlots.length
                            + ", kernelsCompiled=" + kernelsCompiled
                            + ", slotsComputed=" + scheduledSlotCount + "/" + slotCount
                            + ", directBlockedChunks=" + directBlockedChunks
                            + ", stalledChunks=" + stalledChunks
                            + ", totalNoiseOctaves=" + descriptor.totalOctaves
                            + ", totalSourceChars=" + totalSourceChars
                            + ", maxSourceChars=" + maxSourceChars
                            + ", compileMs=" + formatMillis(compileNanos)
                            + ", slotBufferBytes=" + slotBufferBytes
                            + ", slotBufferSlots=" + slotBufferSlotCount
                            + ", slotBufferLayout=" + (compactSlotBuffer ? "compact-slot-major" : "slot-major")
                            + ", cachedInputs=true"
                            + ", noRead=true");
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        } finally {
            for (DfcOpenClDeviceContext.GeneratedNoiseKernel kernel : kernels) {
                if (kernel != null) {
                    kernel.close();
                }
            }
        }
    }

    private static SlabVmCellBenchmark slabVmCellGridRealNoiseBenchmark(NoiseSpec[] specs,
                                                                        int cellWidth, int cellHeight, int cells,
                                                                        int iterations, int warmups,
                                                                        boolean readOutput) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                readOutput, false);
    }

    private static SlabVmCellBenchmark slabVmCellGridRealNoiseBenchmark(NoiseSpec[] specs,
                                                                        int cellWidth, int cellHeight, int cells,
                                                                        int iterations, int warmups,
                                                                        boolean readOutput, boolean reuseInputs) {
        return slabVmCellGridRealNoiseBenchmark(specs, cellWidth, cellHeight, cells, iterations, warmups,
                readOutput, reuseInputs, false);
    }

    private static SlabVmCellBenchmark slabVmCellGridRealNoiseBenchmark(NoiseSpec[] specs,
                                                                        int cellWidth, int cellHeight, int cells,
                                                                        int iterations, int warmups,
                                                                        boolean readOutput, boolean reuseInputs,
                                                                        boolean bySlotFill) {
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromNoiseSpecs(specs);
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid real noise specs: " + errorMessage(throwable));
        }
        return slabVmCellGridNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups, readOutput,
                descriptor, "realNoiseSlots=true", reuseInputs, bySlotFill);
    }

    private static SlabVmCellBenchmark slabVmCellGridNoiseBenchmark(int cellWidth, int cellHeight, int cells,
                                                                    int iterations, int warmups,
                                                                    boolean readOutput,
                                                                    int noiseSlotCount,
                                                                    int noiseOctavesPerBranch) {
        DfcOpenClNoiseDescriptor descriptor =
                DfcOpenClNoiseDescriptor.synthetic(noiseSlotCount, noiseOctavesPerBranch);
        return slabVmCellGridNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups, readOutput,
                descriptor, "noiseSlots=true", false, false);
    }

    private static SlabVmCellBenchmark slabVmCellGridNoiseBenchmark(int cellWidth, int cellHeight, int cells,
                                                                    int iterations, int warmups,
                                                                    boolean readOutput,
                                                                    DfcOpenClNoiseDescriptor descriptor,
                                                                    String descriptorLabel,
                                                                    boolean reuseInputs,
                                                                    boolean bySlotFill) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);
            if (reuseInputs) {
                if (bySlotFill) {
                    context.evalSlabVmCellGridNoiseSlotsBySlot(request, true);
                } else {
                    context.evalSlabVmCellGridNoiseSlots(request, true);
                }
                DfcOpenClSlabVmSmoke.validateNoiseCellGrid(out, safeCellWidth, safeCellHeight, safeCells,
                        descriptor);
            }

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result =
                        bySlotFill
                                ? (reuseInputs
                                        ? context.evalSlabVmCellGridNoiseSlotsBySlotReuseInputs(request, readOutput)
                                        : context.evalSlabVmCellGridNoiseSlotsBySlot(request, readOutput))
                                : (reuseInputs
                                        ? context.evalSlabVmCellGridNoiseSlotsReuseInputs(request, readOutput)
                                        : context.evalSlabVmCellGridNoiseSlots(request, readOutput));
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                if (readOutput) {
                    DfcOpenClSlabVmSmoke.validateNoiseCellGrid(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);
                }
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }
            long totalElements = (long) out.length * safeIterations;
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    totalNanos / safeIterations,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok, " + descriptorLabel + ", slots=" + request.slotCount()
                            + ", branches=" + request.branchesPerSlot()
                            + ", octaves=" + request.octavesPerBranch()
                            + ", totalNoiseOctaves="
                            + kernelNoiseOctaves(request, request.slotCount())
                            + (reuseInputs ? ", cachedInputs=true" : "")
                            + (bySlotFill ? ", bySlotFill=true" : "")
                            + (readOutput ? "" : ", noRead=true"));
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    private static SlabVmCellBenchmark slabVmCellGridRealNoiseDirectBenchmark(NoiseSpec[] specs,
                                                                              int cellWidth, int cellHeight,
                                                                              int cells, int iterations,
                                                                              int warmups, int usedSlots,
                                                                              boolean readOutput) {
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromNoiseSpecs(specs);
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid real noise specs: " + errorMessage(throwable));
        }
        return slabVmCellGridDirectNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups, readOutput,
                descriptor, Math.max(1, usedSlots), "realNoiseDirect=true");
    }

    private static SlabVmCellBenchmark slabVmCellGridDirectNoiseBenchmark(int cellWidth, int cellHeight, int cells,
                                                                          int iterations, int warmups,
                                                                          boolean readOutput,
                                                                          DfcOpenClNoiseDescriptor descriptor,
                                                                          int requestedUsedSlots,
                                                                          String descriptorLabel) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);
            int usedSlots = Math.min(Math.max(1, requestedUsedSlots), request.slotCount());
            context.evalSlabVmCellGridDirectNoise(request, usedSlots, true);
            DfcOpenClSlabVmSmoke.validateDirectNoiseCellGrid(out, safeCellWidth, safeCellHeight, safeCells,
                    descriptor, usedSlots);

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result =
                        context.evalSlabVmCellGridDirectNoiseReuseInputs(request, usedSlots, readOutput);
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                if (readOutput) {
                    DfcOpenClSlabVmSmoke.validateDirectNoiseCellGrid(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor, usedSlots);
                }
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }
            long totalElements = (long) out.length * safeIterations;
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    totalNanos / safeIterations,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok, " + descriptorLabel + ", slots=" + request.slotCount()
                            + ", usedSlots=" + usedSlots
                            + ", branches=" + request.branchesPerSlot()
                            + ", octaves=" + request.octavesPerBranch()
                            + ", totalNoiseOctaves="
                            + kernelNoiseOctaves(request, usedSlots)
                            + ", cachedInputs=true"
                            + (readOutput ? "" : ", noRead=true"));
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    private static SlabVmCellBenchmark slabVmCellGridRealNoiseSourceBenchmark(NoiseSpec[] specs,
                                                                              int cellWidth, int cellHeight,
                                                                              int cells, int iterations,
                                                                              int warmups, boolean readOutput,
                                                                              DfcOpenClGeneratedNoiseSource.WrapMode wrapMode) {
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromNoiseSpecs(specs);
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid real noise specs: " + errorMessage(throwable));
        }

        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);
            int usedSlots = request.slotCount();
            DfcOpenClGeneratedNoiseSource.WrapMode sourceWrapMode = wrapMode != null
                    ? wrapMode
                    : (noWrapAxisSafe(request, usedSlots)
                    ? DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP
                    : DfcOpenClGeneratedNoiseSource.WrapMode.WRAP);
            DfcOpenClGeneratedNoiseSource.BuildResult source =
                    DfcOpenClGeneratedNoiseSource.build(descriptor, usedSlots, sourceWrapMode);
            try (DfcOpenClDeviceContext.GeneratedNoiseKernel generated =
                         context.compileGeneratedNoiseKernel(source.source())) {
                context.evalGeneratedNoiseKernel(generated, request, true);
                DfcOpenClSlabVmSmoke.validateDirectNoiseCellGrid(out, safeCellWidth, safeCellHeight, safeCells,
                        descriptor, usedSlots);

                long totalNanos = 0L;
                long bestNanos = Long.MAX_VALUE;
                long worstNanos = 0L;
                int totalRuns = safeWarmups + safeIterations;
                for (int i = 0; i < totalRuns; i++) {
                    DfcOpenClStats.recordSlabAttempt(out.length);
                    DfcOpenClStats.recordSlabSubmitted();
                    DfcOpenClDeviceContext.SlabVmResult result =
                            context.evalGeneratedNoiseKernelReuseInputs(generated, request, readOutput);
                    DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                    if (readOutput) {
                        DfcOpenClSlabVmSmoke.validateDirectNoiseCellGrid(out, safeCellWidth, safeCellHeight,
                                safeCells, descriptor, usedSlots);
                    }
                    if (i >= safeWarmups) {
                        long nanos = result.elapsedNanos();
                        totalNanos += nanos;
                        bestNanos = Math.min(bestNanos, nanos);
                        worstNanos = Math.max(worstNanos, nanos);
                    }
                }
                long totalElements = (long) out.length * safeIterations;
                long averageKernelNanos = totalNanos / safeIterations;
                return new SlabVmCellBenchmark(
                        true,
                        context.deviceInfo(),
                        safeCellWidth,
                        safeCellHeight,
                        safeCells,
                        safeIterations,
                        safeWarmups,
                        out.length,
                        totalElements,
                        totalNanos,
                        averageKernelNanos,
                        bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                        worstNanos,
                        "ok, realNoiseSource=true, slots=" + request.slotCount()
                                + ", usedSlots=" + usedSlots
                                + ", branches=" + request.branchesPerSlot()
                                + ", octaves=" + request.octavesPerBranch()
                                + ", totalNoiseOctaves=" + kernelNoiseOctaves(request, usedSlots)
                                + ", sourceChars=" + source.source().length()
                                + ", coordTemps=" + source.coordScaleTemps()
                                + ", coordTempRefs=" + source.coordScaleRefs()
                                + ", wrapAxis=" + (wrapMode == null
                                ? "auto-" + wrapModeLabel(sourceWrapMode)
                                : wrapModeLabel(sourceWrapMode))
                                + ", cachedInputs=true"
                                + (readOutput ? "" : ", noRead=true"));
            }
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    private static SlabVmCellBenchmark slabVmCellGridCompiledPlanSourceBenchmark(OpenClCompiledPlan plan,
                                                                                 int cellWidth, int cellHeight,
                                                                                 int cells, int iterations,
                                                                                 int warmups, boolean readOutput,
                                                                                 DfcOpenClGeneratedNoiseSource.WrapMode wrapMode) {
        if (plan == null) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(), "compiled plan is null");
        }
        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        } catch (Throwable throwable) {
            return SlabVmCellBenchmark.failed(status().selectedDevice(),
                    "Invalid compiled plan noise specs: " + errorMessage(throwable));
        }
        int externalSlotCount = externalSlotCount(plan.externalSlots());

        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                    DfcOpenClSlabVmSmoke.noiseCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells,
                            descriptor);
            int usedSlots = request.slotCount();
            int usedExternalSlots = externalSlotCount(plan.externalSlots(), usedSlots);
            int usedComputedSlots = computedSlotCount(plan.computedSlots(), usedSlots);
            int gpuSlots = Math.max(0, usedSlots - usedExternalSlots);
            long externalPrefillNanos = 0L;
            double[] externalSlots = null;
            if (usedExternalSlots > 0) {
                long prefillStarted = System.nanoTime();
                externalSlots = fillExternalSlots(plan, request, usedSlots);
                externalPrefillNanos = System.nanoTime() - prefillStarted;
            }
            DfcOpenClGeneratedNoiseSource.WrapMode sourceWrapMode = wrapMode != null
                    ? wrapMode
                    : (noWrapAxisSafe(request, usedSlots)
                    ? DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP
                    : DfcOpenClGeneratedNoiseSource.WrapMode.WRAP);
            DfcOpenClGeneratedNoiseSource.BuildResult source =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlan(descriptor, usedSlots, plan.slabProgram(),
                            plan.slabConstants(), plan.hoistExpression(),
                            plan.slotCoordXExpressions(), plan.slotCoordYExpressions(), plan.slotCoordZExpressions(),
                            plan.externalSlots(),
                            plan.computedSlots(),
                            sourceWrapMode);
            try (DfcOpenClDeviceContext.GeneratedNoiseKernel generated =
                         context.compileGeneratedNoiseKernel(source.source())) {
                context.evalGeneratedNoiseKernel(generated, request, externalSlots, true);
                validateCompiledPlanCellGrid(out, request, descriptor,
                        plan.slabProgram(), plan.slabConstants(), plan.hoistEvaluator(),
                        plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(), plan.slotCoordZEvaluators(),
                        externalSlots, plan.externalSlots(), plan.computedSlots(),
                        usedSlots);

                long totalNanos = 0L;
                long bestNanos = Long.MAX_VALUE;
                long worstNanos = 0L;
                int totalRuns = safeWarmups + safeIterations;
                for (int i = 0; i < totalRuns; i++) {
                    DfcOpenClStats.recordSlabAttempt(out.length);
                    DfcOpenClStats.recordSlabSubmitted();
                    DfcOpenClDeviceContext.SlabVmResult result =
                            context.evalGeneratedNoiseKernelReuseInputs(generated, request, externalSlots, readOutput);
                    DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                    if (readOutput) {
                        validateCompiledPlanCellGrid(out, request, descriptor,
                                plan.slabProgram(), plan.slabConstants(), plan.hoistEvaluator(),
                                plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(), plan.slotCoordZEvaluators(),
                                externalSlots, plan.externalSlots(), plan.computedSlots(),
                                usedSlots);
                    }
                    if (i >= safeWarmups) {
                        long nanos = result.elapsedNanos();
                        totalNanos += nanos;
                        bestNanos = Math.min(bestNanos, nanos);
                        worstNanos = Math.max(worstNanos, nanos);
                    }
                }
                long totalElements = (long) out.length * safeIterations;
                long averageKernelNanos = totalNanos / safeIterations;
                return new SlabVmCellBenchmark(
                        true,
                        context.deviceInfo(),
                        safeCellWidth,
                        safeCellHeight,
                        safeCells,
                        safeIterations,
                        safeWarmups,
                        out.length,
                        totalElements,
                        totalNanos,
                        averageKernelNanos,
                        bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                        worstNanos,
                        "ok, compiledPlan=true, label=" + plan.label()
                                + ", slots=" + request.slotCount()
                                + ", branches=" + request.branchesPerSlot()
                                + ", octaves=" + request.octavesPerBranch()
                                + ", totalNoiseOctaves=" + descriptor.totalOctaves
                                + ", slabProgramBytes=" + plan.slabProgram().length
                                + ", slabConsts=" + plan.slabConstants().length
                                + ", sourceChars=" + source.source().length()
                                + ", coordTemps=" + source.coordScaleTemps()
                                + ", coordTempRefs=" + source.coordScaleRefs()
                                + ", slotCoords=" + hasPlanSlotCoords(plan, usedSlots)
                                + ", externalSlots=" + externalSlotCount
                                + ", gpuSlots=" + gpuSlots
                                + ", computedSlots=" + usedComputedSlots
                                + ", externalPrefillMs=" + formatMillis(externalPrefillNanos)
                                + ", externalPrefillValueNs=" + formatNanosPerValue(
                                externalPrefillNanos, (long) request.n() * usedExternalSlots)
                                + ", oneShotWithPrefillMs=" + formatMillis(externalPrefillNanos + averageKernelNanos)
                                + ", wrapAxis=" + (wrapMode == null
                                ? "auto-" + wrapModeLabel(sourceWrapMode)
                                : wrapModeLabel(sourceWrapMode))
                                + ", cachedInputs=true"
                                + (readOutput ? "" : ", noRead=true"));
            }
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    private static boolean hasPlanSlotCoords(OpenClCompiledPlan plan, int usedSlots) {
        return plan != null
                && plan.slotCoordXExpressions() != null
                && plan.slotCoordYExpressions() != null
                && plan.slotCoordZExpressions() != null
                && plan.slotCoordXExpressions().length >= usedSlots
                && plan.slotCoordYExpressions().length >= usedSlots
                && plan.slotCoordZExpressions().length >= usedSlots;
    }

    private static SlabVmCellBenchmark slabVmCellGridDirectBenchmark(int cellWidth, int cellHeight, int cells,
                                                                     int iterations, int warmups,
                                                                     boolean readOutput) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmGeneratedCellGridRequest request =
                    DfcOpenClSlabVmSmoke.generatedCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells);

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result =
                        context.evalSlabVmCellGridDirectDemo(request, readOutput);
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                if (readOutput) {
                    DfcOpenClSlabVmSmoke.validateCellCoords(out, safeCellWidth, safeCellHeight, safeCells);
                }
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }
            long totalElements = (long) out.length * safeIterations;
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    totalNanos / safeIterations,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok, directDemo=true" + (readOutput ? "" : ", noRead=true"));
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    private static SlabVmCellBenchmark slabVmCellGridGeneratedBenchmark(int cellWidth, int cellHeight, int cells,
                                                                        int iterations, int warmups,
                                                                        boolean readOutput) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmGeneratedCellGridRequest request =
                    DfcOpenClSlabVmSmoke.generatedCellGridRequest(out, safeCellWidth, safeCellHeight, safeCells);

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result =
                        context.evalSlabVmCellGridGeneratedSlots(request, readOutput);
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                if (readOutput) {
                    DfcOpenClSlabVmSmoke.validateCellCoords(out, safeCellWidth, safeCellHeight, safeCells);
                }
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }
            long totalElements = (long) out.length * safeIterations;
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    totalNanos / safeIterations,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok, generatedSlots=true" + (readOutput ? "" : ", noRead=true"));
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    private static SlabVmCellBenchmark slabVmCellGridBenchmark(int cellWidth, int cellHeight, int cells,
                                                               int iterations, int warmups, boolean readOutput,
                                                               boolean reuseInputs) {
        if (!DfcOpenClConfig.enabled()) {
            return SlabVmCellBenchmark.failed(null, "OpenCL runtime is disabled.");
        }

        Status status = status();
        if (!status.probed() || !status.available() || selectedCandidate == null) {
            status = probe(true);
        }
        if (!status.available() || selectedCandidate == null) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    status.error() == null ? "No available OpenCL device." : status.error());
        }

        int safeCellWidth = Math.min(Math.max(1, cellWidth), 64);
        int safeCellHeight = Math.min(Math.max(1, cellHeight), 512);
        int safeCells = Math.min(Math.max(1, cells), 1 << 20);
        int safeIterations = Math.min(Math.max(1, iterations), 256);
        int safeWarmups = Math.min(Math.max(0, warmups), 64);
        long elements = DfcOpenClSlabVmSmoke.cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int maxElements = DfcOpenClConfig.coordBenchMaxElements();
        if (elements > maxElements) {
            return SlabVmCellBenchmark.failed(status.selectedDevice(),
                    "Requested " + elements + " elements, max=" + maxElements
                            + " (set -Ddfc.opencl.coordBenchMaxElements to raise diagnostic limit).");
        }

        try {
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[Math.toIntExact(elements)];
            DfcOpenClDeviceContext.SlabVmCellGridRequest request =
                    DfcOpenClSlabVmSmoke.cellGridRequest(out, safeCellWidth, safeCellHeight, safeCells);
            if (reuseInputs) {
                context.evalSlabVmCellGrid(request);
                DfcOpenClSlabVmSmoke.validateCellCoords(out, safeCellWidth, safeCellHeight, safeCells);
            }

            long totalNanos = 0L;
            long bestNanos = Long.MAX_VALUE;
            long worstNanos = 0L;
            int totalRuns = safeWarmups + safeIterations;
            for (int i = 0; i < totalRuns; i++) {
                DfcOpenClStats.recordSlabAttempt(out.length);
                DfcOpenClStats.recordSlabSubmitted();
                DfcOpenClDeviceContext.SlabVmResult result;
                if (reuseInputs) {
                    result = readOutput
                            ? context.evalSlabVmCellGridReuseInputs(request)
                            : context.evalSlabVmCellGridNoReadReuseInputs(request);
                } else {
                    result = readOutput
                            ? context.evalSlabVmCellGrid(request)
                            : context.evalSlabVmCellGridNoRead(request);
                }
                DfcOpenClStats.recordSlabSuccess(result.elapsedNanos());
                if (readOutput) {
                    DfcOpenClSlabVmSmoke.validateCellCoords(out, safeCellWidth, safeCellHeight, safeCells);
                }
                if (i >= safeWarmups) {
                    long nanos = result.elapsedNanos();
                    totalNanos += nanos;
                    bestNanos = Math.min(bestNanos, nanos);
                    worstNanos = Math.max(worstNanos, nanos);
                }
            }
            long totalElements = (long) out.length * safeIterations;
            return new SlabVmCellBenchmark(
                    true,
                    context.deviceInfo(),
                    safeCellWidth,
                    safeCellHeight,
                    safeCells,
                    safeIterations,
                    safeWarmups,
                    out.length,
                    totalElements,
                    totalNanos,
                    totalNanos / safeIterations,
                    bestNanos == Long.MAX_VALUE ? 0L : bestNanos,
                    worstNanos,
                    "ok" + (readOutput ? "" : ", noRead=true") + (reuseInputs ? ", cachedInputs=true" : ""));
        } catch (Throwable throwable) {
            DfcOpenClStats.recordSlabFailure();
            closeActiveContext();
            return SlabVmCellBenchmark.failed(status.selectedDevice(), errorMessage(throwable));
        }
    }

    private static RuntimeHybridPlan runtimeHybridPlan(CompiledDensityFunction compiled) {
        synchronized (RUNTIME_HYBRID_PLANS) {
            RuntimeHybridPlan cached = RUNTIME_HYBRID_PLANS.get(compiled);
            if (cached != null) {
                return cached;
            }
            RuntimeHybridPlan built = buildRuntimeHybridPlan(compiled);
            if (runtimeHybridPlanCacheable(built.available())) {
                RUNTIME_HYBRID_PLANS.put(compiled, built);
            }
            return built;
        }
    }

    static boolean runtimeHybridPlanCacheable(boolean available) {
        return !available;
    }

    private static RuntimeHybridPlan buildRuntimeHybridPlan(CompiledDensityFunction compiled) {
        DfcOpenClCompiledPlanRegistry.Entry entry = DfcOpenClCompiledPlanRegistry.lookup(compiled);
        if (!entry.available()) {
            return RuntimeHybridPlan.unavailable(entry.unavailableReason());
        }
        OpenClCompiledPlan plan = DfcOpenClCompiledPlanRegistry.expandMarkerSlots(entry.plan(), 3);
        int slotCount = plan.specs() == null ? 0 : plan.specs().length;
        RuntimeOutputLayer[] outputLayers = new RuntimeOutputLayer[0];
        if (!runtimeHybridCandidateSlotCount(slotCount)) {
            EmbeddedRuntimePlan embedded = findEmbeddedRuntimePlan(plan);
            if (embedded == null) {
                return RuntimeHybridPlan.unavailable("compiled plan has only " + slotCount
                        + " slots; runtime hybrid is reserved for finalDensity-sized plans; "
                        + describeEmbeddedPlanCandidates(plan));
            }
            plan = embedded.plan();
            outputLayers = embedded.outputLayers();
            slotCount = plan.specs() == null ? 0 : plan.specs().length;
        }
        if (plan.slabProgram() == null || plan.slabProgram().length == 0) {
            return RuntimeHybridPlan.unavailable("compiled plan has no final slab program");
        }

        DfcOpenClNoiseDescriptor descriptor;
        try {
            descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                    plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        } catch (Throwable throwable) {
            return RuntimeHybridPlan.unavailable("invalid runtime hybrid noise specs: " + errorMessage(throwable));
        }

        List<RuntimeChunk> chunks = new ArrayList<>();
        collectRuntimeChunks(plan, chunks);
        if (chunks.isEmpty()) {
            return RuntimeHybridPlan.unavailable("no runtime hybrid chunks");
        }

        int[] chunkStartSlots = new int[chunks.size()];
        int[] chunkEndSlots = new int[chunks.size()];
        List<boolean[]> chunkInputs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RuntimeChunk chunk = chunks.get(i);
            chunkStartSlots[i] = chunk.startSlot();
            chunkEndSlots[i] = chunk.endSlot();
            chunkInputs.add(compiledPlanChunkExternalInputs(plan, chunk.startSlot(), chunk.endSlot()));
        }

        int[] slotOwners = runtimeChunkSlotOwners(chunks, slotCount);
        RuntimeWavePlan wavePlan = collectRuntimeChunkWaves(chunkInputs, slotOwners);
        if (wavePlan.waves().length == 0) {
            return RuntimeHybridPlan.unavailable("no schedulable runtime hybrid waves");
        }

        int scheduledSlotCount = scheduledSlotCount(chunkStartSlots, chunkEndSlots, wavePlan.scheduledChunks());
        if (scheduledSlotCount <= 0) {
            return RuntimeHybridPlan.unavailable("runtime hybrid waves contain no slots");
        }
        int[] slotBufferIndices =
                compactSlotBufferIndices(chunkStartSlots, chunkEndSlots, wavePlan.scheduledChunks(), slotCount);
        boolean[] stagedSlots =
                waveTargetSlots(wavePlan.scheduledChunks(), chunkStartSlots, chunkEndSlots, slotCount);

        String[] waveSources = new String[wavePlan.waves().length];
        int totalSourceChars = 0;
        int maxSourceChars = 0;
        for (int wave = 0; wave < wavePlan.waves().length; wave++) {
            boolean[] waveTargetSlots = waveTargetSlots(wavePlan.waves()[wave], chunkStartSlots, chunkEndSlots,
                    slotCount);
            boolean[] waveExternalInputs = waveExternalInputs(plan, wavePlan.waves()[wave],
                    chunkStartSlots, chunkEndSlots, waveTargetSlots);
            ComputedSlot[] waveComputedSlots = waveComputedSlots(plan, waveTargetSlots);
            DfcOpenClGeneratedNoiseSource.BuildResult source =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlanWaveCompactSlotBuffer(
                            descriptor, waveTargetSlots,
                            plan.slotCoordXExpressions(), plan.slotCoordYExpressions(), plan.slotCoordZExpressions(),
                            waveExternalInputs, waveComputedSlots, slotBufferIndices,
                            DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);
            if (source.source().length() > COMPILED_PLAN_FUSED_WAVE_SOURCE_COMPILE_MAX_CHARS) {
                return RuntimeHybridPlan.unavailable("runtime hybrid wave " + wave
                        + " sourceChars " + source.source().length()
                        + ">" + COMPILED_PLAN_FUSED_WAVE_SOURCE_COMPILE_MAX_CHARS);
            }
            waveSources[wave] = source.source();
            totalSourceChars += source.source().length();
            maxSourceChars = Math.max(maxSourceChars, source.source().length());
        }

        return RuntimeHybridPlan.available(plan, descriptor, outputLayers,
                slotCount, scheduledSlotCount, slotBufferIndices, stagedSlots, waveSources,
                identityWaves(waveSources.length), totalSourceChars, maxSourceChars);
    }

    private static EmbeddedRuntimePlan findEmbeddedRuntimePlan(OpenClCompiledPlan outputPlan) {
        DensityFunction[] externs = outputPlan.externs();
        if (externs == null) {
            return null;
        }
        EmbeddedRuntimePlan best = null;
        for (int externIndex = 0; externIndex < externs.length; externIndex++) {
            OpenClCompiledPlan plan = embeddedOpenClPlan(externs[externIndex]);
            if (plan == null) {
                continue;
            }
            int slots = plan.specs() == null ? 0 : plan.specs().length;
            EmbeddedRuntimePlan candidate;
            RuntimeOutputLayer currentLayer = runtimeOutputLayer(outputPlan, externIndex);
            if (runtimeHybridCandidateSlotCount(slots)) {
                candidate = new EmbeddedRuntimePlan(plan, slots, new RuntimeOutputLayer[]{currentLayer});
            } else {
                EmbeddedRuntimePlan nested = findEmbeddedRuntimePlan(plan);
                if (nested == null) {
                    continue;
                }
                RuntimeOutputLayer[] layers = new RuntimeOutputLayer[nested.outputLayers().length + 1];
                layers[0] = currentLayer;
                System.arraycopy(nested.outputLayers(), 0, layers, 1, nested.outputLayers().length);
                candidate = new EmbeddedRuntimePlan(nested.plan(), nested.slotCount(), layers);
            }
            if (best == null || candidate.slotCount() > best.slotCount()) {
                best = candidate;
            }
        }
        return best;
    }

    private static RuntimeOutputLayer runtimeOutputLayer(OpenClCompiledPlan plan, int embeddedExternIndex) {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.fromCompiledPlan(
                plan.specs(), plan.blendedSpecs(), inactiveSlots(plan));
        return new RuntimeOutputLayer(plan, descriptor, embeddedExternIndex);
    }

    private static OpenClCompiledPlan embeddedOpenClPlan(DensityFunction extern) {
        DensityFunction candidate = extern;
        if (candidate instanceof DensityFunctions.MarkerOrMarked marker) {
            candidate = marker.wrapped();
        }
        if (candidate == null) {
            return null;
        }
        try {
            CompiledDensityFunction compiled = null;
            if (candidate instanceof CompiledDensityFunction c) {
                compiled = c;
            }
            if (compiled == null) {
                return null;
            }
            DfcOpenClCompiledPlanRegistry.Entry entry = DfcOpenClCompiledPlanRegistry.lookup(compiled);
            return entry.available()
                    ? DfcOpenClCompiledPlanRegistry.expandMarkerSlots(entry.plan(), 3)
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describeEmbeddedPlanCandidates(OpenClCompiledPlan outputPlan) {
        return describeEmbeddedPlanCandidates(outputPlan, 0);
    }

    private static String describeEmbeddedPlanCandidates(OpenClCompiledPlan outputPlan, int depth) {
        DensityFunction[] externs = outputPlan.externs();
        int[] markerExternIndices = outputPlan.markerExternIndices();
        boolean[] externalSlots = outputPlan.externalSlots();
        StringBuilder out = new StringBuilder();
        out.append("plan{slots=").append(outputPlan.specs() == null ? 0 : outputPlan.specs().length)
                .append(", label=").append(outputPlan.label()).append(", externs=");
        int externCount = externs == null ? 0 : externs.length;
        out.append(externCount).append('[');
        int emitted = 0;
        for (int externIndex = 0; externIndex < externCount && emitted < 4; externIndex++) {
            DensityFunction extern = externs[externIndex];
            if (emitted > 0) {
                out.append("; ");
            }
            out.append(externIndex).append(':').append(shortClassName(extern));
            if (extern instanceof DensityFunctions.MarkerOrMarked marker) {
                DensityFunction wrapped = marker.wrapped();
                out.append("{marker=").append(marker.type())
                        .append(", wrapped=").append(shortClassName(wrapped))
                        .append(", wrappedPlan=").append(describeCompiledPlanLookup(wrapped))
                        .append('}');
                appendNestedPlanDescription(out, wrapped, depth);
            } else {
                out.append("{plan=").append(describeCompiledPlanLookup(extern)).append('}');
                appendNestedPlanDescription(out, extern, depth);
            }
            emitted++;
        }
        if (externCount > emitted) {
            out.append("; +").append(externCount - emitted);
        }
        out.append("], externalSlots=");
        if (externalSlots == null || markerExternIndices == null) {
            out.append("n/a");
        } else {
            out.append('[');
            int slots = 0;
            for (int slot = 0; slot < externalSlots.length && slots < 6; slot++) {
                if (!externalSlots[slot]) {
                    continue;
                }
                if (slots > 0) {
                    out.append(',');
                }
                out.append(slot).append("->").append(
                        slot < markerExternIndices.length ? markerExternIndices[slot] : -1);
                slots++;
            }
            out.append(']');
        }
        out.append('}');
        return out.toString();
    }

    private static void appendNestedPlanDescription(StringBuilder out, DensityFunction function, int depth) {
        if (depth >= 3 || !(function instanceof CompiledDensityFunction compiled)) {
            return;
        }
        DfcOpenClCompiledPlanRegistry.Entry entry = DfcOpenClCompiledPlanRegistry.lookup(compiled);
        if (!entry.available()) {
            return;
        }
        OpenClCompiledPlan nested = DfcOpenClCompiledPlanRegistry.expandMarkerSlots(entry.plan(), 3);
        int slots = nested.specs() == null ? 0 : nested.specs().length;
        if (slots >= RUNTIME_FINAL_MIN_SLOTS || nested.externs() == null || nested.externs().length == 0) {
            return;
        }
        out.append(", nested=").append(describeEmbeddedPlanCandidates(nested, depth + 1));
    }

    private static String describeCompiledPlanLookup(DensityFunction function) {
        if (function instanceof CompiledDensityFunction compiled) {
            DfcOpenClCompiledPlanRegistry.Entry entry = DfcOpenClCompiledPlanRegistry.lookup(compiled);
            if (!entry.available()) {
                return "unavailable:" + entry.unavailableReason();
            }
            OpenClCompiledPlan plan = entry.plan();
            return "slots=" + (plan.specs() == null ? 0 : plan.specs().length)
                    + ", label=" + plan.label();
        }
        return "not-compiled";
    }

    private static String shortClassName(Object object) {
        if (object == null) {
            return "null";
        }
        String name = object.getClass().getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static void fillRuntimeHybridFinalDensity(
            double[] out,
            NoiseChunk chunk,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            RuntimeHybridPlan runtimePlan,
            double[] slotBuffer) {
        int cellWidth = request.cellWidth();
        int cellHeight = request.cellHeight();
        int safeUsedSlots = Math.min(Math.max(1, runtimePlan.slotCount()), runtimePlan.descriptor().slotCount);
        int[] rootSlots = slotDependencies(runtimePlan.plan().slabProgram(), safeUsedSlots);
        int idx = 0;
        chunk.arrayIndex = 0;
        for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
            chunk.inCellX = inCellX;
            for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                chunk.inCellZ = inCellZ;
                for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                    chunk.inCellY = inCellY;
                    chunk.arrayIndex = idx;

                    int element = runtimeCellFillElementIndex(idx, cellWidth, cellHeight);
                    double bx = chunk.cellStartBlockX + inCellX;
                    double by = chunk.cellStartBlockY + inCellY;
                    double bz = chunk.cellStartBlockZ + inCellZ;
                    HybridResolveResult hybrid = resolveHybridFinalDensitySlotValues(
                            slotBuffer, request, runtimePlan.descriptor(), runtimePlan.plan(),
                            runtimePlan.slotBufferIndices(), runtimePlan.stagedSlots(),
                            element, safeUsedSlots, bx, by, bz, chunk, rootSlots);
                    double hoist = runtimePlan.plan().hoistEvaluator() == null
                            ? 0.0D
                            : runtimePlan.plan().hoistEvaluator().evaluate(bx, by, bz);
                    double baseValue = evalCompiledPlanProgram(runtimePlan.plan().slabProgram(),
                            runtimePlan.plan().slabConstants(), hybrid.slots(), bx, by, bz, hoist);
                    out[idx] = runtimePlan.outputLayers().length == 0
                            ? baseValue
                            : evalRuntimeHybridOutputLayers(runtimePlan.outputLayers(), bx, by, bz, chunk, baseValue);
                    idx++;
                }
            }
        }
        chunk.arrayIndex = idx;
    }

    private static double evalRuntimeHybridOutputLayers(RuntimeOutputLayer[] outputLayers,
                                                        double bx,
                                                        double by,
                                                        double bz,
                                                        DensityFunction.FunctionContext context,
                                                        double embeddedValue) {
        double value = embeddedValue;
        for (int layerIndex = outputLayers.length - 1; layerIndex >= 0; layerIndex--) {
            value = evalRuntimeHybridOutputLayer(outputLayers[layerIndex], bx, by, bz, context, value);
        }
        return value;
    }

    private static double evalRuntimeHybridOutputLayer(RuntimeOutputLayer layer,
                                                       double bx,
                                                       double by,
                                                       double bz,
                                                       DensityFunction.FunctionContext context,
                                                       double embeddedValue) {
        OpenClCompiledPlan plan = layer.plan();
        int safeUsedSlots = plan.specs() == null ? 0 : plan.specs().length;
        if (safeUsedSlots <= 0) {
            return embeddedValue;
        }
        double[] slots = new double[safeUsedSlots];
        boolean[] resolvedSlots = new boolean[safeUsedSlots];
        boolean[] visitingSlots = new boolean[safeUsedSlots];
        for (int slot = 0; slot < safeUsedSlots; slot++) {
            resolveRuntimeHybridOutputSlot(layer, slot, safeUsedSlots, bx, by, bz, context,
                    embeddedValue, slots, resolvedSlots, visitingSlots);
        }
        double hoist = plan.hoistEvaluator() == null ? 0.0D : plan.hoistEvaluator().evaluate(bx, by, bz);
        return evalCompiledPlanProgram(plan.slabProgram(), plan.slabConstants(), slots, bx, by, bz, hoist);
    }

    private static double resolveRuntimeHybridOutputSlot(RuntimeOutputLayer layer,
                                                         int slot,
                                                         int safeUsedSlots,
                                                         double bx,
                                                         double by,
                                                         double bz,
                                                         DensityFunction.FunctionContext context,
                                                         double embeddedValue,
                                                         double[] slots,
                                                         boolean[] resolvedSlots,
                                                         boolean[] visitingSlots) {
        if (slot < 0 || slot >= safeUsedSlots) {
            throw new IllegalStateException("runtime hybrid output plan references slot outside batch: " + slot);
        }
        if (resolvedSlots[slot]) {
            return slots[slot];
        }
        if (visitingSlots[slot]) {
            throw new IllegalStateException("cyclic runtime hybrid output slot dependency at slot " + slot);
        }
        OpenClCompiledPlan outputPlan = layer.plan();
        visitingSlots[slot] = true;
        try {
            ComputedSlot computed = computedSlot(outputPlan.computedSlots(), slot);
            if (computed != null) {
                for (int dependency : slotDependencies(computed.slabProgram(), safeUsedSlots)) {
                    resolveRuntimeHybridOutputSlot(layer, dependency, safeUsedSlots, bx, by, bz, context,
                            embeddedValue, slots, resolvedSlots, visitingSlots);
                }
                double hoist = computed.hoistEvaluator() == null
                        ? 0.0D
                        : computed.hoistEvaluator().evaluate(bx, by, bz);
                slots[slot] = evalCompiledPlanProgram(computed.slabProgram(), computed.slabConstants(),
                        slots, bx, by, bz, hoist);
            } else if (isExternalSlot(outputPlan.externalSlots(), slot)) {
                int externIndex = markerExternIndex(outputPlan, slot);
                if (externIndex == layer.embeddedExternIndex()) {
                    slots[slot] = embeddedValue;
                } else {
                    slots[slot] = markerExtern(outputPlan, slot).compute(context);
                }
            } else {
                double sx = evalSlotCoord(outputPlan.slotCoordXEvaluators(), slot, bx, by, bz, bx);
                double sy = evalSlotCoord(outputPlan.slotCoordYEvaluators(), slot, bx, by, bz, by);
                double sz = evalSlotCoord(outputPlan.slotCoordZEvaluators(), slot, bx, by, bz, bz);
                slots[slot] = layer.descriptor().sampleSlot(slot, sx, sy, sz);
            }
        } finally {
            visitingSlots[slot] = false;
        }
        resolvedSlots[slot] = true;
        return slots[slot];
    }

    static int runtimeCellFillElementIndex(int javaFillIndex, int cellWidth, int cellHeight) {
        int planeSize = Math.multiplyExact(cellWidth, cellWidth);
        int column = javaFillIndex / cellHeight;
        int yIndex = javaFillIndex - column * cellHeight;
        int inCellX = column / cellWidth;
        int inCellZ = column - inCellX * cellWidth;
        return Math.addExact(Math.multiplyExact(yIndex, planeSize), inCellX * cellWidth + inCellZ);
    }

    static boolean runtimeHybridCandidateSlotCount(int slotCount) {
        return slotCount >= RUNTIME_FINAL_MIN_SLOTS;
    }

    static boolean runtimeHybridSlotValuesMeetMinimum(int slotValues) {
        return slotValues >= DfcOpenClConfig.finalDensityHybridMinSlotValues();
    }

    static boolean runtimeHybridCellValuesCanReachMinimum(int cellValues) {
        if (cellValues <= 0) {
            return false;
        }
        return Math.multiplyExact(cellValues, RUNTIME_FINAL_MIN_SLOTS)
                >= DfcOpenClConfig.finalDensityHybridMinSlotValues();
    }

    private static DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest runtimeNoiseCellGridRequest(
            double[] out,
            int cellWidth,
            int cellHeight,
            NoiseChunk chunk,
            DfcOpenClNoiseDescriptor descriptor) {
        int n = Math.multiplyExact(Math.multiplyExact(cellWidth, cellWidth), cellHeight);
        return new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                DfcOpenClSlabVmSmoke.bytecode(),
                DfcOpenClSlabVmSmoke.constants(),
                descriptor.permutations,
                descriptor.origins,
                descriptor.inputFactors,
                descriptor.ampFactors,
                descriptor.branchOctaveOffsets,
                descriptor.branchOctaveCounts,
                descriptor.branchCoordScales,
                descriptor.slotValueFactors,
                descriptor.slotCount,
                descriptor.branchesPerSlot,
                descriptor.octavesPerBranch,
                chunk.cellStartBlockX,
                chunk.cellStartBlockY,
                chunk.cellStartBlockZ,
                cellWidth,
                cellHeight,
                1,
                0.0D,
                out,
                n);
    }

    private static DfcOpenClDeviceContext ensureActiveContext() {
        DfcOpenClDeviceEnumerator.Candidate candidate = selectedCandidate;
        if (candidate == null) {
            throw new IllegalStateException("No selected OpenCL device");
        }
        if (activeContext != null && activeContext.isOpen() && activeContext.isFor(candidate)) {
            return activeContext;
        }
        closeActiveContext();
        activeContext = DfcOpenClDeviceContext.create(candidate);
        if (activeContext.buildLog() != null && !activeContext.buildLog().isBlank()) {
            LOGGER.info("DFC OpenCL runtime build log: {}", activeContext.buildLog());
        }
        return activeContext;
    }

    private static void closeActiveContext() {
        synchronized (RUNTIME_HYBRID_PLANS) {
            RUNTIME_HYBRID_PLANS.clear();
        }
        if (activeContext != null) {
            activeContext.close();
            activeContext = null;
        }
    }

    private static boolean sameCandidate(DfcOpenClDeviceEnumerator.Candidate left,
                                         DfcOpenClDeviceEnumerator.Candidate right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.platform() == right.platform() && left.device() == right.device();
    }

    private static void logProbeResult(Status status) {
        if (status.available()) {
            LOGGER.info("DFC OpenCL: {} suitable device(s) found.", status.devices().size());
            int limit = Math.min(status.devices().size(), DfcOpenClConfig.maxLoggedDevices());
            for (int i = 0; i < limit; i++) {
                LOGGER.info("DFC OpenCL device[{}]: {}", i, status.devices().get(i).shortDescription());
            }
            if (status.devices().size() > limit) {
                LOGGER.info("DFC OpenCL: {} more device(s) hidden by dfc.opencl.maxLoggedDevices.",
                        status.devices().size() - limit);
            }
            if (status.runtimeTested()) {
                LOGGER.info("DFC OpenCL: runtime smoke test passed on {}.",
                        status.selectedDevice() == null ? "unknown device" : status.selectedDevice().shortDescription());
                if (status.runtimeBuildLog() != null && !status.runtimeBuildLog().isBlank()) {
                    LOGGER.info("DFC OpenCL build log: {}", status.runtimeBuildLog());
                }
            } else {
                LOGGER.info("DFC OpenCL: runtime smoke test skipped by dfc.opencl.compileSmokeTestOnProbe=false.");
            }
        } else {
            LOGGER.warn("DFC OpenCL: no suitable devices found. Current filters: gpu={}, cpu={}, accelerator={}, fp64Required={}, compileSmokeTest={}, filter='{}'.{}",
                    DfcOpenClConfig.allowGpuDevices(),
                    DfcOpenClConfig.allowCpuDevices(),
                    DfcOpenClConfig.allowAcceleratorDevices(),
                    DfcOpenClConfig.requireFp64(),
                    DfcOpenClConfig.compileSmokeTestOnProbe(),
                    DfcOpenClConfig.deviceFilter(),
                    status.error() == null ? "" : " Error: " + status.error());
        }
    }

    private static String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static void validateCompiledPlanCellGrid(double[] out,
                                                     DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
                                                     DfcOpenClNoiseDescriptor descriptor,
                                                     byte[] program,
                                                     double[] constants,
                                                     HoistEvaluator hoistEvaluator,
                                                     HoistEvaluator[] slotCoordXEvaluators,
                                                     HoistEvaluator[] slotCoordYEvaluators,
                                                     HoistEvaluator[] slotCoordZEvaluators,
                                                     double[] externalSlotValues,
                                                     boolean[] externalSlots,
                                                     ComputedSlot[] computedSlots,
                                                     int usedSlotCount) {
        int n = request.n();
        if (out.length < n) {
            throw new IllegalStateException("OpenCL compiled plan output too short: " + out.length);
        }
        int checks = Math.min(n, 257);
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        for (int check = 0; check < checks; check++) {
            int i = checks == 1 ? 0 : (int) ((long) check * (n - 1) / (checks - 1));
            double bx = cellBlockX(i, request);
            double by = cellBlockY(i, request);
            double bz = cellBlockZ(i, request);
            double[] slots = new double[safeUsedSlots];
            boolean[] resolvedSlots = new boolean[safeUsedSlots];
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                resolveCompiledPlanSlot(slot, slots, resolvedSlots, i, safeUsedSlots, bx, by, bz,
                        descriptor, slotCoordXEvaluators, slotCoordYEvaluators, slotCoordZEvaluators,
                        externalSlotValues, externalSlots, computedSlots);
            }
            double hoist = hoistEvaluator.evaluate(bx, by, bz);
            double expected = evalCompiledPlanProgram(program, constants, slots, bx, by, bz, hoist);
            double actual = out[i];
            if (!Double.isFinite(actual) || Math.abs(actual - expected) > COMPILED_PLAN_EPSILON) {
                throw new IllegalStateException("OpenCL compiled plan mismatch at " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private static FinalOutputValidation validateCompiledPlanFinalOutput(
            double[] out,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            OpenClCompiledPlan plan,
            double[] originalExternalSlotValues,
            int usedSlotCount,
            int maxChecks) {
        int n = request.n();
        if (out.length < n) {
            throw new IllegalStateException("OpenCL compiled final output too short: " + out.length);
        }
        int checks = Math.min(n, Math.max(1, maxChecks));
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        double maxAbsError = 0.0D;
        for (int check = 0; check < checks; check++) {
            int i = checks == 1 ? 0 : (int) ((long) check * (n - 1) / (checks - 1));
            double bx = cellBlockX(i, request);
            double by = cellBlockY(i, request);
            double bz = cellBlockZ(i, request);
            double[] slots = new double[safeUsedSlots];
            boolean[] resolvedSlots = new boolean[safeUsedSlots];
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                resolveCompiledPlanSlot(slot, slots, resolvedSlots, i, safeUsedSlots, bx, by, bz,
                        descriptor, plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(),
                        plan.slotCoordZEvaluators(), originalExternalSlotValues, plan.externalSlots(),
                        plan.computedSlots());
            }
            double hoist = plan.hoistEvaluator() == null ? 0.0D : plan.hoistEvaluator().evaluate(bx, by, bz);
            double expected = evalCompiledPlanProgram(
                    plan.slabProgram(), plan.slabConstants(), slots, bx, by, bz, hoist);
            double actual = out[i];
            double absError = equivalentCompiledPlanValue(actual, expected) ? 0.0D : Math.abs(actual - expected);
            maxAbsError = Math.max(maxAbsError, absError);
            if (absError > COMPILED_PLAN_EPSILON || Double.isNaN(absError)) {
                throw new IllegalStateException("OpenCL compiled final output mismatch at element "
                        + i + ": expected=" + expected + ", actual=" + actual
                        + ", absError=" + absError);
            }
        }
        return new FinalOutputValidation(checks, maxAbsError);
    }

    private static void validateCompiledPlanChunkGrid(double[] out,
                                                      DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
                                                      DfcOpenClNoiseDescriptor descriptor,
                                                      int startSlot,
                                                      int endSlot,
                                                      HoistEvaluator[] slotCoordXEvaluators,
                                                      HoistEvaluator[] slotCoordYEvaluators,
                                                      HoistEvaluator[] slotCoordZEvaluators,
                                                      double[] originalExternalSlotValues,
                                                      boolean[] originalExternalSlots,
                                                      ComputedSlot[] computedSlots,
                                                      int usedSlotCount) {
        int n = request.n();
        if (out.length < n) {
            throw new IllegalStateException("OpenCL compiled chunk output too short: " + out.length);
        }
        int checks = Math.min(n, 257);
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        int safeStart = Math.max(0, Math.min(startSlot, safeUsedSlots - 1));
        int safeEnd = Math.max(safeStart, Math.min(endSlot, safeUsedSlots - 1));
        for (int check = 0; check < checks; check++) {
            int i = checks == 1 ? 0 : (int) ((long) check * (n - 1) / (checks - 1));
            double bx = cellBlockX(i, request);
            double by = cellBlockY(i, request);
            double bz = cellBlockZ(i, request);
            double[] slots = new double[safeUsedSlots];
            boolean[] resolvedSlots = new boolean[safeUsedSlots];
            double expected = 0.0D;
            for (int slot = safeStart; slot <= safeEnd; slot++) {
                expected += resolveCompiledPlanSlot(slot, slots, resolvedSlots, i, safeUsedSlots, bx, by, bz,
                        descriptor, slotCoordXEvaluators, slotCoordYEvaluators, slotCoordZEvaluators,
                        originalExternalSlotValues, originalExternalSlots, computedSlots);
            }
            double actual = out[i];
            if (!Double.isFinite(actual) || Math.abs(actual - expected) > COMPILED_PLAN_EPSILON) {
                throw new IllegalStateException("OpenCL compiled chunk mismatch at " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    static WaveSlotBufferValidation validateCompiledPlanWaveSlotBuffer(
            double[] slotBuffer,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            int[] slotBufferIndices,
            boolean[] targetSlots,
            HoistEvaluator[] slotCoordXEvaluators,
            HoistEvaluator[] slotCoordYEvaluators,
            HoistEvaluator[] slotCoordZEvaluators,
            double[] originalExternalSlotValues,
            boolean[] originalExternalSlots,
            ComputedSlot[] computedSlots,
            int usedSlotCount,
            int maxChecks) {
        int n = request.n();
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        int checks = Math.min(n, Math.max(1, maxChecks));
        int checkedSlots = 0;
        double maxAbsError = 0.0D;
        for (int check = 0; check < checks; check++) {
            int element = checks == 1 ? 0 : (int) ((long) check * (n - 1) / (checks - 1));
            double bx = cellBlockX(element, request);
            double by = cellBlockY(element, request);
            double bz = cellBlockZ(element, request);
            double[] slots = new double[safeUsedSlots];
            boolean[] resolvedSlots = new boolean[safeUsedSlots];
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                if (targetSlots == null || slot >= targetSlots.length || !targetSlots[slot]) {
                    continue;
                }
                int compactIndex = slotBufferIndex(slotBufferIndices, slot);
                int bufferIndex = Math.addExact(Math.multiplyExact(compactIndex, n), element);
                if (slotBuffer == null || bufferIndex >= slotBuffer.length) {
                    throw new IllegalStateException("OpenCL wave slot buffer is missing slot "
                            + slot + " for element " + element);
                }
                double expected = resolveCompiledPlanSlot(slot, slots, resolvedSlots, element, safeUsedSlots,
                        bx, by, bz, descriptor, slotCoordXEvaluators, slotCoordYEvaluators, slotCoordZEvaluators,
                        originalExternalSlotValues, originalExternalSlots, computedSlots);
                double actual = slotBuffer[bufferIndex];
                double absError = Math.abs(actual - expected);
                maxAbsError = Math.max(maxAbsError, absError);
                if (!Double.isFinite(actual) || absError > COMPILED_PLAN_EPSILON) {
                    throw new IllegalStateException("OpenCL compiled wave slot mismatch at element "
                            + element + ", slot=" + slot + ": expected=" + expected
                            + ", actual=" + actual + ", absError=" + absError);
                }
                checkedSlots++;
            }
        }
        return new WaveSlotBufferValidation(checks, checkedSlots, maxAbsError);
    }

    static HybridFinalDensityValidation validateCompiledPlanHybridFinalDensity(
            double[] slotBuffer,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            OpenClCompiledPlan plan,
            int[] slotBufferIndices,
            boolean[] stagedSlots,
            int usedSlotCount,
            int maxChecks) {
        int n = request.n();
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        int checks = Math.min(n, Math.max(1, maxChecks));
        double maxAbsError = 0.0D;
        int stagedReads = 0;
        for (int check = 0; check < checks; check++) {
            int element = checks == 1 ? 0 : (int) ((long) check * (n - 1) / (checks - 1));
            double bx = cellBlockX(element, request);
            double by = cellBlockY(element, request);
            double bz = cellBlockZ(element, request);

            double[] referenceSlots = new double[safeUsedSlots];
            boolean[] referenceResolved = new boolean[safeUsedSlots];
            double[] originalExternalSlotValues = null;
            if (plan.externalSlots() != null) {
                originalExternalSlotValues = new double[Math.multiplyExact(n, safeUsedSlots)];
                DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(
                        (int) bx, (int) by, (int) bz);
                for (int slot = 0; slot < safeUsedSlots; slot++) {
                    if (isExternalSlot(plan.externalSlots(), slot)) {
                        originalExternalSlotValues[elementSlotIndex(element, safeUsedSlots, slot)] =
                                markerExtern(plan, slot).compute(context);
                    }
                }
            }
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                resolveCompiledPlanSlot(slot, referenceSlots, referenceResolved, element, safeUsedSlots,
                        bx, by, bz, descriptor, plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(),
                        plan.slotCoordZEvaluators(), originalExternalSlotValues, plan.externalSlots(),
                        plan.computedSlots());
            }
            double hoist = plan.hoistEvaluator() == null ? 0.0D : plan.hoistEvaluator().evaluate(bx, by, bz);
            double expected = evalCompiledPlanProgram(
                    plan.slabProgram(), plan.slabConstants(), referenceSlots, bx, by, bz, hoist);

            HybridResolveResult hybrid = resolveHybridFinalDensitySlotValues(slotBuffer, request, descriptor, plan,
                    slotBufferIndices, stagedSlots, element, safeUsedSlots, bx, by, bz);
            stagedReads += hybrid.stagedReads();
            double actual = evalCompiledPlanProgram(
                    plan.slabProgram(), plan.slabConstants(), hybrid.slots(), bx, by, bz, hoist);
            double absError = equivalentCompiledPlanValue(actual, expected) ? 0.0D : Math.abs(actual - expected);
            maxAbsError = Math.max(maxAbsError, absError);
            if (absError > COMPILED_PLAN_EPSILON || Double.isNaN(absError)) {
                throw new IllegalStateException("OpenCL compiled hybrid finalDensity mismatch at element "
                        + element + ": expected=" + expected + ", actual=" + actual
                        + ", absError=" + absError);
            }
        }
        return new HybridFinalDensityValidation(checks, stagedReads, maxAbsError);
    }

    private static HybridFinishStats finishCompiledPlanHybridFinalDensity(
            double[] out,
            double[] slotBuffer,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            OpenClCompiledPlan plan,
            int[] slotBufferIndices,
            boolean[] stagedSlots,
            int usedSlotCount) {
        int n = request.n();
        if (out == null || out.length < n) {
            throw new IllegalArgumentException("hybrid output is shorter than request.n");
        }
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        double[] slots = new double[safeUsedSlots];
        boolean[] resolvedSlots = new boolean[safeUsedSlots];
        boolean[] visitingSlots = new boolean[safeUsedSlots];
        int[] stagedReads = new int[1];
        MutableFunctionContext externalContext = new MutableFunctionContext();
        int[] rootSlots = slotDependencies(plan.slabProgram(), safeUsedSlots);
        long totalStagedReads = 0L;
        double checksum = 0.0D;
        for (int element = 0; element < n; element++) {
            Arrays.fill(resolvedSlots, false);
            Arrays.fill(visitingSlots, false);
            stagedReads[0] = 0;

            double bx = cellBlockX(element, request);
            double by = cellBlockY(element, request);
            double bz = cellBlockZ(element, request);
            externalContext.set((int) bx, (int) by, (int) bz);

            for (int slot : rootSlots) {
                resolveHybridSlot(slotBuffer, request, descriptor, plan, slotBufferIndices, stagedSlots,
                        element, safeUsedSlots, bx, by, bz, externalContext,
                        slots, resolvedSlots, visitingSlots, stagedReads, slot);
            }
            double hoist = plan.hoistEvaluator() == null ? 0.0D : plan.hoistEvaluator().evaluate(bx, by, bz);
            double value = evalCompiledPlanProgram(plan.slabProgram(), plan.slabConstants(), slots, bx, by, bz, hoist);
            out[element] = value;
            if ((element & 1023) == 0) {
                checksum += value;
            }
            totalStagedReads += stagedReads[0];
        }
        return new HybridFinishStats(totalStagedReads, checksum);
    }

    private static boolean equivalentCompiledPlanValue(double actual, double expected) {
        if (Double.isNaN(actual) || Double.isNaN(expected)) {
            return Double.isNaN(actual) && Double.isNaN(expected);
        }
        if (Double.isInfinite(actual) || Double.isInfinite(expected)) {
            return actual == expected;
        }
        return false;
    }

    private static HybridResolveResult resolveHybridFinalDensitySlotValues(
            double[] slotBuffer,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            OpenClCompiledPlan plan,
            int[] slotBufferIndices,
            boolean[] stagedSlots,
            int element,
            int safeUsedSlots,
            double bx,
            double by,
            double bz) {
        return resolveHybridFinalDensitySlotValues(slotBuffer, request, descriptor, plan, slotBufferIndices,
                stagedSlots, element, safeUsedSlots, bx, by, bz, null);
    }

    private static HybridResolveResult resolveHybridFinalDensitySlotValues(
            double[] slotBuffer,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            OpenClCompiledPlan plan,
            int[] slotBufferIndices,
            boolean[] stagedSlots,
            int element,
            int safeUsedSlots,
            double bx,
            double by,
            double bz,
            DensityFunction.FunctionContext externalContext) {
        return resolveHybridFinalDensitySlotValues(slotBuffer, request, descriptor, plan, slotBufferIndices,
                stagedSlots, element, safeUsedSlots, bx, by, bz, externalContext, null);
    }

    private static HybridResolveResult resolveHybridFinalDensitySlotValues(
            double[] slotBuffer,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            OpenClCompiledPlan plan,
            int[] slotBufferIndices,
            boolean[] stagedSlots,
            int element,
            int safeUsedSlots,
            double bx,
            double by,
            double bz,
            DensityFunction.FunctionContext externalContext,
            int[] rootSlots) {
        double[] slots = new double[safeUsedSlots];
        boolean[] resolvedSlots = new boolean[safeUsedSlots];
        boolean[] visitingSlots = new boolean[safeUsedSlots];
        int[] stagedReads = new int[1];
        if (rootSlots == null) {
            rootSlots = new int[safeUsedSlots];
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                rootSlots[slot] = slot;
            }
        }
        for (int slot : rootSlots) {
            resolveHybridSlot(slotBuffer, request, descriptor, plan, slotBufferIndices, stagedSlots,
                    element, safeUsedSlots, bx, by, bz, externalContext,
                    slots, resolvedSlots, visitingSlots, stagedReads, slot);
        }
        return new HybridResolveResult(slots, stagedReads[0]);
    }

    private static double resolveHybridSlot(double[] slotBuffer,
                                            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
                                            DfcOpenClNoiseDescriptor descriptor,
                                            OpenClCompiledPlan plan,
                                            int[] slotBufferIndices,
                                            boolean[] stagedSlots,
                                            int element,
                                            int safeUsedSlots,
                                            double bx,
                                            double by,
                                            double bz,
                                            DensityFunction.FunctionContext externalContext,
                                            double[] slots,
                                            boolean[] resolvedSlots,
                                            boolean[] visitingSlots,
                                            int[] stagedReads,
                                            int slot) {
        if (slot < 0 || slot >= safeUsedSlots) {
            throw new IllegalStateException("compiled hybrid plan references slot outside batch: " + slot);
        }
        if (resolvedSlots[slot]) {
            return slots[slot];
        }
        if (stagedSlots != null && slot < stagedSlots.length && stagedSlots[slot]) {
            int compactIndex = slotBufferIndex(slotBufferIndices, slot);
            int bufferIndex = Math.addExact(Math.multiplyExact(compactIndex, request.n()), element);
            if (slotBuffer == null || bufferIndex >= slotBuffer.length) {
                throw new IllegalStateException("OpenCL hybrid slot buffer is missing slot "
                        + slot + " for element " + element);
            }
            slots[slot] = slotBuffer[bufferIndex];
            resolvedSlots[slot] = true;
            stagedReads[0]++;
            return slots[slot];
        }
        if (visitingSlots[slot]) {
            throw new IllegalStateException("cyclic compiled hybrid slot dependency at slot " + slot);
        }
        visitingSlots[slot] = true;
        try {
            ComputedSlot computed = computedSlot(plan.computedSlots(), slot);
            if (computed != null) {
                for (int dependency : slotDependencies(computed.slabProgram(), safeUsedSlots)) {
                    resolveHybridSlot(slotBuffer, request, descriptor, plan, slotBufferIndices, stagedSlots,
                            element, safeUsedSlots, bx, by, bz, externalContext, slots, resolvedSlots, visitingSlots,
                            stagedReads, dependency);
                }
                double hoist = computed.hoistEvaluator() == null
                        ? 0.0D
                        : computed.hoistEvaluator().evaluate(bx, by, bz);
                slots[slot] = evalCompiledPlanProgram(computed.slabProgram(), computed.slabConstants(),
                        slots, bx, by, bz, hoist);
            } else if (isExternalSlot(plan.externalSlots(), slot)) {
                DensityFunction extern = markerExtern(plan, slot);
                DensityFunction.FunctionContext context = externalContext == null
                        ? new DensityFunction.SinglePointContext((int) bx, (int) by, (int) bz)
                        : externalContext;
                slots[slot] = extern.compute(context);
            } else {
                double sx = evalSlotCoord(plan.slotCoordXEvaluators(), slot, bx, by, bz, bx);
                double sy = evalSlotCoord(plan.slotCoordYEvaluators(), slot, bx, by, bz, by);
                double sz = evalSlotCoord(plan.slotCoordZEvaluators(), slot, bx, by, bz, bz);
                slots[slot] = descriptor.sampleSlot(slot, sx, sy, sz);
            }
        } finally {
            visitingSlots[slot] = false;
        }
        resolvedSlots[slot] = true;
        return slots[slot];
    }

    private record HybridResolveResult(double[] slots, int stagedReads) {
    }

    private record HybridFinishStats(long stagedReads, double checksum) {
        static HybridFinishStats empty() {
            return new HybridFinishStats(0L, 0.0D);
        }
    }

    private static final class MutableFunctionContext implements DensityFunction.FunctionContext {
        private int blockX;
        private int blockY;
        private int blockZ;

        void set(int blockX, int blockY, int blockZ) {
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
        }

        @Override
        public int blockX() {
            return this.blockX;
        }

        @Override
        public int blockY() {
            return this.blockY;
        }

        @Override
        public int blockZ() {
            return this.blockZ;
        }
    }

    private static int slotBufferIndex(int[] slotBufferIndices, int slot) {
        if (slotBufferIndices == null) {
            return slot;
        }
        if (slot < 0 || slot >= slotBufferIndices.length || slotBufferIndices[slot] < 0) {
            throw new IllegalStateException("OpenCL wave slot " + slot + " has no compact slot buffer index");
        }
        return slotBufferIndices[slot];
    }

    private static double resolveCompiledPlanSlot(int slot, double[] slots, boolean[] resolvedSlots,
                                                  int element, int safeUsedSlots,
                                                  double bx, double by, double bz,
                                                  DfcOpenClNoiseDescriptor descriptor,
                                                  HoistEvaluator[] slotCoordXEvaluators,
                                                  HoistEvaluator[] slotCoordYEvaluators,
                                                  HoistEvaluator[] slotCoordZEvaluators,
                                                  double[] externalSlotValues,
                                                  boolean[] externalSlots,
                                                  ComputedSlot[] computedSlots) {
        if (slot < 0 || slot >= safeUsedSlots) {
            throw new IllegalStateException("compiled plan references slot outside batch: " + slot);
        }
        if (resolvedSlots[slot]) {
            return slots[slot];
        }
        ComputedSlot computed = computedSlot(computedSlots, slot);
        if (computed != null) {
            for (int dependency : slotDependencies(computed.slabProgram(), safeUsedSlots)) {
                resolveCompiledPlanSlot(dependency, slots, resolvedSlots, element, safeUsedSlots, bx, by, bz,
                        descriptor, slotCoordXEvaluators, slotCoordYEvaluators, slotCoordZEvaluators,
                        externalSlotValues, externalSlots, computedSlots);
            }
            double hoist = computed.hoistEvaluator() == null ? 0.0D : computed.hoistEvaluator().evaluate(bx, by, bz);
            slots[slot] = evalCompiledPlanProgram(computed.slabProgram(), computed.slabConstants(),
                    slots, bx, by, bz, hoist);
        } else if (isExternalSlot(externalSlots, slot)) {
            int index = elementSlotIndex(element, safeUsedSlots, slot);
            if (externalSlotValues == null || externalSlotValues.length <= index) {
                throw new IllegalStateException("OpenCL compiled plan external slot buffer is missing slot "
                        + slot + " for element " + element);
            }
            slots[slot] = externalSlotValues[index];
        } else {
            double sx = evalSlotCoord(slotCoordXEvaluators, slot, bx, by, bz, bx);
            double sy = evalSlotCoord(slotCoordYEvaluators, slot, bx, by, bz, by);
            double sz = evalSlotCoord(slotCoordZEvaluators, slot, bx, by, bz, bz);
            slots[slot] = descriptor.sampleSlot(slot, sx, sy, sz);
        }
        resolvedSlots[slot] = true;
        return slots[slot];
    }

    static double evalCompiledPlanProgram(byte[] program, double[] constants, double[] slots,
                                          double bx, double by, double bz, double hoist) {
        double[] stack = new double[192];
        int sp = 0;
        for (int pc = 0; pc < program.length;) {
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST -> {
                    int idx = readU16(program, pc);
                    pc += 2;
                    stack[sp++] = constants[idx];
                }
                case OP_PUSH_SLOT -> stack[sp++] = slots[program[pc++] & 0xFF];
                case OP_COND_NEG_SCALE -> {
                    int idx = readU16(program, pc);
                    pc += 2;
                    double x = stack[--sp];
                    stack[sp++] = x > 0.0D ? x : x * constants[idx];
                }
                case OP_Y_CLAMPED_GRADIENT -> {
                    int fromY = readU16(program, pc); pc += 2;
                    int toY = readU16(program, pc); pc += 2;
                    int fromValue = readU16(program, pc); pc += 2;
                    int toValue = readU16(program, pc); pc += 2;
                    stack[sp++] = clampedMap(by, constants[fromY], constants[toY],
                            constants[fromValue], constants[toValue]);
                }
                case OP_RANGE_CHOICE -> {
                    int min = readU16(program, pc); pc += 2;
                    int max = readU16(program, pc); pc += 2;
                    double whenOut = stack[--sp];
                    double whenIn = stack[--sp];
                    double input = stack[--sp];
                    stack[sp++] = input >= constants[min] && input < constants[max] ? whenIn : whenOut;
                }
                case OP_RANGE_CHOICE_JUMP -> {
                    int min = readU16(program, pc); pc += 2;
                    int max = readU16(program, pc); pc += 2;
                    int whenInPc = readI32(program, pc); pc += 4;
                    int whenOutPc = readI32(program, pc); pc += 4;
                    double input = stack[--sp];
                    pc = input >= constants[min] && input < constants[max] ? whenInPc : whenOutPc;
                }
                case OP_JUMP -> pc = readI32(program, pc);
                case OP_BLOCK_X -> stack[sp++] = bx;
                case OP_BLOCK_Y -> stack[sp++] = by;
                case OP_BLOCK_Z -> stack[sp++] = bz;
                case OP_HOIST -> stack[sp++] = hoist;
                case OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX -> {
                    double right = stack[--sp];
                    double left = stack[--sp];
                    stack[sp++] = switch (op) {
                        case OP_ADD -> left + right;
                        case OP_SUB -> left - right;
                        case OP_MUL -> left * right;
                        case OP_DIV -> left / right;
                        case OP_MIN -> Math.min(left, right);
                        case OP_MAX -> Math.max(left, right);
                        default -> throw new IllegalStateException("not a binary opcode " + op);
                    };
                }
                case OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                    double x = stack[--sp];
                    stack[sp++] = switch (op) {
                        case OP_NEG -> -x;
                        case OP_ABS -> Math.abs(x);
                        case OP_SQUARE -> x * x;
                        case OP_SQUEEZE -> squeeze(x);
                        default -> throw new IllegalStateException("not a unary opcode " + op);
                    };
                }
                default -> throw new IllegalStateException("unsupported compiled plan opcode " + op);
            }
        }
        if (sp != 1) {
            throw new IllegalStateException("compiled plan program ended with stack depth " + sp);
        }
        return stack[0];
    }

    private static double evalSlotCoord(HoistEvaluator[] evaluators, int slot,
                                        double bx, double by, double bz, double fallback) {
        if (evaluators == null || slot < 0 || slot >= evaluators.length || evaluators[slot] == null) {
            return fallback;
        }
        return evaluators[slot].evaluate(bx, by, bz);
    }

    private static double[] fillExternalSlots(OpenClCompiledPlan plan,
                                               DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
                                               int usedSlotCount) {
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), request.slotCount());
        double[] values = new double[Math.multiplyExact(request.n(), safeUsedSlots)];
        int[] externalSlotIndices = compiledPlanExternalSlotIndices(plan.externalSlots(), safeUsedSlots);
        for (int i = 0; i < request.n(); i++) {
            int bx = (int) cellBlockX(i, request);
            int by = (int) cellBlockY(i, request);
            int bz = (int) cellBlockZ(i, request);
            DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(bx, by, bz);
            for (int slot : externalSlotIndices) {
                DensityFunction extern = markerExtern(plan, slot);
                values[elementSlotIndex(i, safeUsedSlots, slot)] = extern.compute(context);
            }
        }
        return values;
    }

    static int[] compiledPlanExternalSlotIndices(boolean[] externalSlots, int usedSlotCount) {
        int limit = Math.min(externalSlots == null ? 0 : externalSlots.length, Math.max(0, usedSlotCount));
        int[] slots = new int[limit];
        int count = 0;
        for (int slot = 0; slot < limit; slot++) {
            if (externalSlots[slot]) {
                slots[count++] = slot;
            }
        }
        return Arrays.copyOf(slots, count);
    }

    private static double[] fillChunkExternalInputs(OpenClCompiledPlan plan,
                                                    DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
                                                    DfcOpenClNoiseDescriptor descriptor,
                                                    boolean[] chunkExternalInputs,
                                                    double[] originalExternalSlotValues,
                                                    int usedSlotCount) {
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), request.slotCount());
        double[] values = new double[Math.multiplyExact(request.n(), safeUsedSlots)];
        for (int i = 0; i < request.n(); i++) {
            double bx = cellBlockX(i, request);
            double by = cellBlockY(i, request);
            double bz = cellBlockZ(i, request);
            double[] slots = new double[safeUsedSlots];
            boolean[] resolvedSlots = new boolean[safeUsedSlots];
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                if (chunkExternalInputs == null || slot >= chunkExternalInputs.length || !chunkExternalInputs[slot]) {
                    continue;
                }
                values[elementSlotIndex(i, safeUsedSlots, slot)] = resolveCompiledPlanSlot(
                        slot, slots, resolvedSlots, i, safeUsedSlots, bx, by, bz,
                        descriptor,
                        plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(), plan.slotCoordZEvaluators(),
                        originalExternalSlotValues, plan.externalSlots(), plan.computedSlots());
            }
        }
        return values;
    }

    private static double[] fillFinalOutputExternalInputs(OpenClCompiledPlan plan,
                                                          DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
                                                          DfcOpenClNoiseDescriptor descriptor,
                                                          boolean[] externalInputs,
                                                          double[] originalExternalSlotValues,
                                                          int usedSlotCount) {
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), request.slotCount());
        double[] values = new double[Math.multiplyExact(request.n(), safeUsedSlots)];
        for (int i = 0; i < request.n(); i++) {
            double bx = cellBlockX(i, request);
            double by = cellBlockY(i, request);
            double bz = cellBlockZ(i, request);
            double[] slots = new double[safeUsedSlots];
            boolean[] resolvedSlots = new boolean[safeUsedSlots];
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                if (externalInputs == null || slot >= externalInputs.length || !externalInputs[slot]) {
                    continue;
                }
                values[elementSlotIndex(i, safeUsedSlots, slot)] = resolveCompiledPlanSlot(
                        slot, slots, resolvedSlots, i, safeUsedSlots, bx, by, bz,
                        descriptor,
                        plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(), plan.slotCoordZEvaluators(),
                        originalExternalSlotValues, plan.externalSlots(), plan.computedSlots());
            }
        }
        return values;
    }

    private static FinalOutputSlotBufferInputs fillFinalOutputSlotBufferInputs(
            OpenClCompiledPlan plan,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            boolean[] inputSlots,
            double[] originalExternalSlotValues,
            int[] slotBufferIndices,
            int slotBufferSlotCount,
            boolean traceTimings) {
        int[] directExternalInputSlots = finalOutputDirectExternalInputSlots(
                plan, inputSlots, request.slotCount());
        if (directExternalInputSlots != null) {
            return fillFinalOutputDirectExternalSlotBufferInputs(
                    request, originalExternalSlotValues, slotBufferIndices, slotBufferSlotCount,
                    directExternalInputSlots, traceTimings);
        }
        if (traceTimings) {
            return fillFinalOutputSlotBufferInputsTrace(
                    plan, request, descriptor, inputSlots, originalExternalSlotValues,
                    slotBufferIndices, slotBufferSlotCount);
        }
        double[] values = new double[Math.multiplyExact(request.n(), slotBufferSlotCount)];
        if (inputSlots == null) {
            return new FinalOutputSlotBufferInputs(values, null);
        }
        int slotLimit = Math.min(inputSlots.length, request.slotCount());
        for (int element = 0; element < request.n(); element++) {
            double bx = cellBlockX(element, request);
            double by = cellBlockY(element, request);
            double bz = cellBlockZ(element, request);
            double[] slots = new double[slotLimit];
            boolean[] resolvedSlots = new boolean[slotLimit];
            for (int slot = 0; slot < slotLimit; slot++) {
                if (!inputSlots[slot]) {
                    continue;
                }
                int compactIndex = slotBufferIndex(slotBufferIndices, slot);
                values[Math.addExact(Math.multiplyExact(compactIndex, request.n()), element)] =
                        resolveCompiledPlanSlot(
                                slot, slots, resolvedSlots, element, slotLimit, bx, by, bz,
                                descriptor,
                                plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(),
                                plan.slotCoordZEvaluators(),
                                originalExternalSlotValues, plan.externalSlots(), plan.computedSlots());
            }
        }
        return new FinalOutputSlotBufferInputs(values, null);
    }

    private static FinalOutputSlotBufferInputs fillFinalOutputSlotBufferInputsTrace(
            OpenClCompiledPlan plan,
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            DfcOpenClNoiseDescriptor descriptor,
            boolean[] inputSlots,
            double[] originalExternalSlotValues,
            int[] slotBufferIndices,
            int slotBufferSlotCount) {
        long totalStarted = System.nanoTime();
        long allocateStarted = totalStarted;
        double[] values = new double[Math.multiplyExact(request.n(), slotBufferSlotCount)];
        long allocateNanos = System.nanoTime() - allocateStarted;
        int slotLimit = inputSlots == null ? 0 : Math.min(inputSlots.length, request.slotCount());
        long[] slotNanos = new long[slotLimit];
        int[] slotValues = new int[slotLimit];
        long setupNanos = 0L;
        long scanNanos = 0L;
        if (inputSlots != null) {
            for (int element = 0; element < request.n(); element++) {
                long setupStarted = System.nanoTime();
                double bx = cellBlockX(element, request);
                double by = cellBlockY(element, request);
                double bz = cellBlockZ(element, request);
                double[] slots = new double[slotLimit];
                boolean[] resolvedSlots = new boolean[slotLimit];
                setupNanos += System.nanoTime() - setupStarted;

                long scanStarted = System.nanoTime();
                long activeSlotNanos = 0L;
                for (int slot = 0; slot < slotLimit; slot++) {
                    if (!inputSlots[slot]) {
                        continue;
                    }
                    long slotStarted = System.nanoTime();
                    int compactIndex = slotBufferIndex(slotBufferIndices, slot);
                    values[Math.addExact(Math.multiplyExact(compactIndex, request.n()), element)] =
                            resolveCompiledPlanSlot(
                                    slot, slots, resolvedSlots, element, slotLimit, bx, by, bz,
                                    descriptor,
                                    plan.slotCoordXEvaluators(), plan.slotCoordYEvaluators(),
                                    plan.slotCoordZEvaluators(),
                                    originalExternalSlotValues, plan.externalSlots(), plan.computedSlots());
                    long elapsed = System.nanoTime() - slotStarted;
                    slotNanos[slot] += elapsed;
                    slotValues[slot]++;
                    activeSlotNanos += elapsed;
                }
                long scanElapsed = System.nanoTime() - scanStarted;
                scanNanos += Math.max(0L, scanElapsed - activeSlotNanos);
            }
        }
        FinalOutputExternalPrefillTrace trace = new FinalOutputExternalPrefillTrace(
                System.nanoTime() - totalStarted, allocateNanos, setupNanos, scanNanos, slotNanos, slotValues);
        return new FinalOutputSlotBufferInputs(values, trace);
    }

    static int[] finalOutputDirectExternalInputSlots(OpenClCompiledPlan plan, boolean[] inputSlots, int slotCount) {
        if (inputSlots == null) {
            return new int[0];
        }
        int limit = Math.min(inputSlots.length, Math.max(0, slotCount));
        int[] slots = new int[limit];
        int count = 0;
        for (int slot = 0; slot < limit; slot++) {
            if (!inputSlots[slot]) {
                continue;
            }
            if (!isExternalSlot(plan.externalSlots(), slot)) {
                return null;
            }
            slots[count++] = slot;
        }
        return Arrays.copyOf(slots, count);
    }

    private static FinalOutputSlotBufferInputs fillFinalOutputDirectExternalSlotBufferInputs(
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            double[] originalExternalSlotValues,
            int[] slotBufferIndices,
            int slotBufferSlotCount,
            int[] directExternalInputSlots,
            boolean traceTimings) {
        long totalStarted = traceTimings ? System.nanoTime() : 0L;
        long allocateStarted = traceTimings ? totalStarted : 0L;
        double[] values = new double[Math.multiplyExact(request.n(), slotBufferSlotCount)];
        long allocateNanos = traceTimings ? System.nanoTime() - allocateStarted : 0L;
        long[] slotNanos = traceTimings ? new long[Math.max(0, request.slotCount())] : null;
        int[] slotValues = traceTimings ? new int[Math.max(0, request.slotCount())] : null;
        for (int slot : directExternalInputSlots) {
            long slotStarted = traceTimings ? System.nanoTime() : 0L;
            int compactIndex = slotBufferIndex(slotBufferIndices, slot);
            copyDirectExternalSlotBufferInput(
                    request, originalExternalSlotValues, values, slot, compactIndex);
            if (traceTimings && slot >= 0 && slot < slotNanos.length) {
                slotNanos[slot] += System.nanoTime() - slotStarted;
                slotValues[slot] += request.n();
            }
        }
        FinalOutputExternalPrefillTrace trace = traceTimings
                ? new FinalOutputExternalPrefillTrace(
                System.nanoTime() - totalStarted, allocateNanos, 0L, 0L, slotNanos, slotValues)
                : null;
        return new FinalOutputSlotBufferInputs(values, trace);
    }

    private static void copyDirectExternalSlotBufferInput(
            DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request,
            double[] originalExternalSlotValues,
            double[] values,
            int slot,
            int compactIndex) {
        int n = request.n();
        int sourceSlotCount = request.slotCount();
        if (n > 0) {
            int lastSourceIndex = elementSlotIndex(n - 1, sourceSlotCount, slot);
            if (originalExternalSlotValues == null || originalExternalSlotValues.length <= lastSourceIndex) {
                throw new IllegalStateException("OpenCL compiled plan external slot buffer is missing slot "
                        + slot + " for element " + (n - 1));
            }
        }
        int sourceIndex = slot;
        int targetIndex = Math.multiplyExact(compactIndex, n);
        for (int element = 0; element < n; element++) {
            values[targetIndex + element] = originalExternalSlotValues[sourceIndex];
            sourceIndex += sourceSlotCount;
        }
    }

    private static DensityFunction markerExtern(OpenClCompiledPlan plan, int slot) {
        int externIndex = markerExternIndex(plan, slot);
        DensityFunction[] externs = plan.externs();
        if (externs == null || externIndex >= externs.length || externs[externIndex] == null) {
            throw new IllegalStateException("compiled plan external slot " + slot
                    + " has invalid marker extern index " + externIndex);
        }
        return externs[externIndex];
    }

    private static int markerExternIndex(OpenClCompiledPlan plan, int slot) {
        int[] markerExternIndices = plan.markerExternIndices();
        if (markerExternIndices == null || slot < 0 || slot >= markerExternIndices.length) {
            throw new IllegalStateException("compiled plan external slot " + slot + " has no marker extern index");
        }
        int externIndex = markerExternIndices[slot];
        if (externIndex < 0) {
            throw new IllegalStateException("compiled plan external slot " + slot
                    + " has invalid marker extern index " + externIndex);
        }
        return externIndex;
    }

    private static boolean[] scheduledChunks(boolean[][] waves, int chunkCount) {
        boolean[] scheduled = new boolean[Math.max(0, chunkCount)];
        if (waves == null) {
            return scheduled;
        }
        for (boolean[] wave : waves) {
            if (wave == null) {
                continue;
            }
            int limit = Math.min(wave.length, scheduled.length);
            for (int chunk = 0; chunk < limit; chunk++) {
                scheduled[chunk] |= wave[chunk];
            }
        }
        return scheduled;
    }

    private static int scheduledSlotCount(int[] chunkStartSlots, int[] chunkEndSlots, boolean[] scheduledChunks) {
        int count = 0;
        int limit = Math.min(Math.min(chunkStartSlots.length, chunkEndSlots.length), scheduledChunks.length);
        for (int chunk = 0; chunk < limit; chunk++) {
            if (scheduledChunks[chunk]) {
                count += Math.max(0, chunkEndSlots[chunk] - chunkStartSlots[chunk] + 1);
            }
        }
        return count;
    }

    private static int[] compactSlotBufferIndices(int[] chunkStartSlots, int[] chunkEndSlots,
                                                  boolean[] scheduledChunks, int slotCount) {
        int[] indices = new int[Math.max(0, slotCount)];
        Arrays.fill(indices, -1);
        int next = 0;
        int limit = Math.min(Math.min(chunkStartSlots.length, chunkEndSlots.length), scheduledChunks.length);
        for (int chunk = 0; chunk < limit; chunk++) {
            if (!scheduledChunks[chunk]) {
                continue;
            }
            int start = Math.max(0, Math.min(chunkStartSlots[chunk], indices.length - 1));
            int end = Math.max(start, Math.min(chunkEndSlots[chunk], indices.length - 1));
            for (int slot = start; slot <= end; slot++) {
                indices[slot] = next++;
            }
        }
        return indices;
    }

    private static int[] compactSlotBufferIndices(boolean[] slotBufferSlots, int slotCount) {
        int[] indices = new int[Math.max(0, slotCount)];
        Arrays.fill(indices, -1);
        int limit = Math.min(indices.length, slotBufferSlots == null ? 0 : slotBufferSlots.length);
        int next = 0;
        for (int slot = 0; slot < limit; slot++) {
            if (slotBufferSlots[slot]) {
                indices[slot] = next++;
            }
        }
        return indices;
    }

    private static boolean[] waveTargetSlots(boolean[] wave, int[] chunkStartSlots, int[] chunkEndSlots,
                                             int slotCount) {
        boolean[] targetSlots = new boolean[Math.max(0, slotCount)];
        if (wave == null) {
            return targetSlots;
        }
        int limit = Math.min(Math.min(chunkStartSlots.length, chunkEndSlots.length), wave.length);
        for (int chunk = 0; chunk < limit; chunk++) {
            if (!wave[chunk]) {
                continue;
            }
            int start = Math.max(0, Math.min(chunkStartSlots[chunk], targetSlots.length - 1));
            int end = Math.max(start, Math.min(chunkEndSlots[chunk], targetSlots.length - 1));
            for (int slot = start; slot <= end; slot++) {
                targetSlots[slot] = true;
            }
        }
        return targetSlots;
    }

    private static boolean[] unionSlots(boolean[] left, boolean[] right, int slotCount) {
        boolean[] out = new boolean[Math.max(0, slotCount)];
        for (int slot = 0; slot < out.length; slot++) {
            out[slot] = (left != null && slot < left.length && left[slot])
                    || (right != null && slot < right.length && right[slot]);
        }
        return out;
    }

    private static boolean[] singleSlotMask(int targetSlot, int slotCount) {
        boolean[] out = new boolean[Math.max(0, slotCount)];
        if (targetSlot >= 0 && targetSlot < out.length) {
            out[targetSlot] = true;
        }
        return out;
    }

    private static boolean[] slotsExcept(boolean[] slots, int excludedSlot, int slotCount) {
        boolean[] out = new boolean[Math.max(0, slotCount)];
        int limit = Math.min(out.length, slots == null ? 0 : slots.length);
        for (int slot = 0; slot < limit; slot++) {
            out[slot] = slots[slot] && slot != excludedSlot;
        }
        return out;
    }

    private static boolean[] slotsExcept(boolean[] slots, boolean[] excludedSlots, int slotCount) {
        boolean[] out = new boolean[Math.max(0, slotCount)];
        int limit = Math.min(out.length, slots == null ? 0 : slots.length);
        for (int slot = 0; slot < limit; slot++) {
            out[slot] = slots[slot]
                    && !(excludedSlots != null && slot < excludedSlots.length && excludedSlots[slot]);
        }
        return out;
    }

    static boolean[] residualDependencyNoiseBatchSlots(boolean[] residualDependencyCandidateSlots,
                                                       boolean[] finalResidualDependencySlots,
                                                       ComputedSlot[] computedSlots,
                                                       int slotCount) {
        boolean[] out = new boolean[Math.max(0, slotCount)];
        int limit = Math.min(out.length, residualDependencyCandidateSlots == null
                ? 0 : residualDependencyCandidateSlots.length);
        for (int slot = 0; slot < limit; slot++) {
            out[slot] = residualDependencyCandidateSlots[slot]
                    && !(finalResidualDependencySlots != null
                    && slot < finalResidualDependencySlots.length
                    && finalResidualDependencySlots[slot])
                    && computedSlot(computedSlots, slot) == null;
        }
        return out;
    }

    static boolean[] finalOutputWaveTargetsWithResidualNoise(boolean[] scheduledSlots,
                                                             boolean[] residualDependencyCandidateSlots,
                                                             ComputedSlot[] computedSlots,
                                                             int slotCount) {
        return unionSlots(scheduledSlots,
                residualDependencyNoiseBatchSlots(residualDependencyCandidateSlots, null, computedSlots, slotCount),
                slotCount);
    }

    private static int firstTrueSlot(boolean[] slots) {
        if (slots != null) {
            for (int slot = 0; slot < slots.length; slot++) {
                if (slots[slot]) {
                    return slot;
                }
            }
        }
        return -1;
    }

    static String describeFinalOutputInputSlots(OpenClCompiledPlan plan, boolean[] slots, int limit) {
        int count = countTrue(slots);
        StringBuilder out = new StringBuilder();
        out.append(count).append('[');
        int emitted = 0;
        int safeLimit = Math.max(0, limit);
        if (slots != null) {
            for (int slot = 0; slot < slots.length; slot++) {
                if (!slots[slot]) {
                    continue;
                }
                if (emitted > 0) {
                    out.append("; ");
                }
                if (emitted >= safeLimit) {
                    out.append('+').append(count - emitted);
                    break;
                }
                out.append(slot).append(':').append(finalOutputSlotKind(plan, slot));
                emitted++;
            }
        }
        out.append(']');
        return out.toString();
    }

    private static String finalOutputSlotKind(OpenClCompiledPlan plan, int slot) {
        if (isExternalSlot(plan.externalSlots(), slot)) {
            StringBuilder out = new StringBuilder("external#");
            try {
                int externIndex = markerExternIndex(plan, slot);
                out.append(externIndex).append(':');
                DensityFunction[] externs = plan.externs();
                out.append(externs != null && externIndex >= 0 && externIndex < externs.length
                        ? shortClassName(externs[externIndex])
                        : "missing");
            } catch (RuntimeException exception) {
                out.append("invalid");
            }
            return out.toString();
        }
        ComputedSlot computed = computedSlot(plan.computedSlots(), slot);
        if (computed != null) {
            return computed.label() == null || computed.label().isBlank()
                    ? "computed"
                    : "computed:" + computed.label();
        }
        return "noise";
    }

    private static boolean[] slabProgramRootSlots(byte[] program, int slotCount) {
        boolean[] roots = new boolean[Math.max(0, slotCount)];
        for (int slot : slotDependencies(program, roots.length)) {
            roots[slot] = true;
        }
        return roots;
    }

    static boolean[] compiledPlanFinalOutputRootSlots(OpenClCompiledPlan plan, int slotCount) {
        boolean[] roots = slabProgramRootSlots(plan.slabProgram(), slotCount);
        if (slabProgramUsesHoist(plan.slabProgram())) {
            markSlotExpressionDependencies(plan.hoistExpression(), roots, dependency -> roots[dependency] = true);
        }
        return roots;
    }

    static boolean[] compiledPlanFinalOutputResidualGpuInputSlots(OpenClCompiledPlan plan,
                                                                  boolean[] scheduledSlots,
                                                                  int slotCount) {
        boolean[] roots = compiledPlanFinalOutputRootSlots(plan, slotCount);
        boolean[] gpuInputs = new boolean[Math.max(0, slotCount)];
        for (int slot = 0; slot < gpuInputs.length; slot++) {
            boolean scheduled = scheduledSlots != null && slot < scheduledSlots.length && scheduledSlots[slot];
            if (roots[slot] && !scheduled && !isExternalSlot(plan.externalSlots(), slot)) {
                gpuInputs[slot] = true;
            }
        }
        return gpuInputs;
    }

    static boolean[] compiledPlanFinalOutputResidualDependencySlots(OpenClCompiledPlan plan,
                                                                    boolean[] residualCandidateSlots,
                                                                    boolean[] scheduledSlots,
                                                                    boolean[] markerExternalInputs,
                                                                    int slotCount) {
        int length = Math.max(0, slotCount);
        boolean[] dependencies = new boolean[length];
        if (residualCandidateSlots == null) {
            return dependencies;
        }
        boolean[] visited = new boolean[length];
        boolean[] visiting = new boolean[length];
        int limit = Math.min(length, residualCandidateSlots.length);
        for (int slot = 0; slot < limit; slot++) {
            if (!residualCandidateSlots[slot]) {
                continue;
            }
            ComputedSlot computed = computedSlot(plan.computedSlots(), slot);
            if (computed == null) {
                continue;
            }
            for (int dependency : slotDependencies(computed.slabProgram(), length)) {
                collectResidualDependencySlot(plan, dependencies, residualCandidateSlots, scheduledSlots,
                        markerExternalInputs, visited, visiting, dependency);
            }
            if (slabProgramUsesHoist(computed.slabProgram())) {
                markSlotExpressionDependencies(computed.hoistExpression(), dependencies, dependency -> {
                    collectResidualDependencySlot(plan, dependencies, residualCandidateSlots, scheduledSlots,
                            markerExternalInputs, visited, visiting, dependency);
                });
            }
        }
        return dependencies;
    }

    private static void collectResidualDependencySlot(OpenClCompiledPlan plan,
                                                      boolean[] dependencies,
                                                      boolean[] residualCandidateSlots,
                                                      boolean[] scheduledSlots,
                                                      boolean[] markerExternalInputs,
                                                      boolean[] visited,
                                                      boolean[] visiting,
                                                      int slot) {
        if (slot < 0 || slot >= dependencies.length) {
            return;
        }
        if (residualCandidateSlots != null && slot < residualCandidateSlots.length && residualCandidateSlots[slot]) {
            return;
        }
        if (scheduledSlots != null && slot < scheduledSlots.length && scheduledSlots[slot]) {
            return;
        }
        if (markerExternalInputs != null && slot < markerExternalInputs.length && markerExternalInputs[slot]) {
            return;
        }
        if (isExternalSlot(plan.externalSlots(), slot)) {
            return;
        }
        dependencies[slot] = true;
        if (visited[slot]) {
            return;
        }
        if (visiting[slot]) {
            throw new IllegalStateException("cyclic residual dependency slot at " + slot);
        }
        visiting[slot] = true;
        try {
            ComputedSlot computed = computedSlot(plan.computedSlots(), slot);
            if (computed != null) {
                for (int dependency : slotDependencies(computed.slabProgram(), dependencies.length)) {
                    collectResidualDependencySlot(plan, dependencies, residualCandidateSlots, scheduledSlots,
                            markerExternalInputs, visited, visiting, dependency);
                }
                if (slabProgramUsesHoist(computed.slabProgram())) {
                    markSlotExpressionDependencies(computed.hoistExpression(), dependencies, dependency -> {
                        collectResidualDependencySlot(plan, dependencies, residualCandidateSlots, scheduledSlots,
                                markerExternalInputs, visited, visiting, dependency);
                    });
                }
            } else {
                collectResidualCoordinateDependencySlots(plan, dependencies, residualCandidateSlots, scheduledSlots,
                        markerExternalInputs, visited, visiting, slot);
            }
        } finally {
            visiting[slot] = false;
        }
        visited[slot] = true;
    }

    private static void collectResidualCoordinateDependencySlots(OpenClCompiledPlan plan,
                                                                 boolean[] dependencies,
                                                                 boolean[] residualCandidateSlots,
                                                                 boolean[] scheduledSlots,
                                                                 boolean[] markerExternalInputs,
                                                                 boolean[] visited,
                                                                 boolean[] visiting,
                                                                 int slot) {
        collectResidualExpressionDependencySlots(expressionAt(plan.slotCoordXExpressions(), slot),
                plan, dependencies, residualCandidateSlots, scheduledSlots, markerExternalInputs, visited, visiting);
        collectResidualExpressionDependencySlots(expressionAt(plan.slotCoordYExpressions(), slot),
                plan, dependencies, residualCandidateSlots, scheduledSlots, markerExternalInputs, visited, visiting);
        collectResidualExpressionDependencySlots(expressionAt(plan.slotCoordZExpressions(), slot),
                plan, dependencies, residualCandidateSlots, scheduledSlots, markerExternalInputs, visited, visiting);
    }

    private static void collectResidualExpressionDependencySlots(String expression,
                                                                 OpenClCompiledPlan plan,
                                                                 boolean[] dependencies,
                                                                 boolean[] residualCandidateSlots,
                                                                 boolean[] scheduledSlots,
                                                                 boolean[] markerExternalInputs,
                                                                 boolean[] visited,
                                                                 boolean[] visiting) {
        markSlotExpressionDependencies(expression, dependencies, dependency -> {
            collectResidualDependencySlot(plan, dependencies, residualCandidateSlots, scheduledSlots,
                    markerExternalInputs, visited, visiting, dependency);
        });
    }

    static int residualDependencyCpuFallbackSlot(boolean[] candidates,
                                                 boolean[] gpuSlots,
                                                 boolean[] cpuSlots,
                                                 long[] rejectedSourceChars) {
        if (candidates == null || rejectedSourceChars == null) {
            return -1;
        }
        int fallbackSlot = -1;
        long fallbackChars = Long.MAX_VALUE;
        int limit = Math.min(candidates.length, rejectedSourceChars.length);
        for (int slot = 0; slot < limit; slot++) {
            if (!candidates[slot]
                    || (gpuSlots != null && slot < gpuSlots.length && gpuSlots[slot])
                    || (cpuSlots != null && slot < cpuSlots.length && cpuSlots[slot])) {
                continue;
            }
            long chars = rejectedSourceChars[slot];
            if (chars <= 0L) {
                continue;
            }
            if (chars < fallbackChars) {
                fallbackChars = chars;
                fallbackSlot = slot;
            }
        }
        return fallbackSlot;
    }

    static boolean computedSlotDependenciesStaged(ComputedSlot computed,
                                                  boolean[] stagedInputs,
                                                  int targetSlot,
                                                  int slotCount) {
        if (computed == null || stagedInputs == null) {
            return false;
        }
        int length = Math.max(0, slotCount);
        for (int dependency : slotDependencies(computed.slabProgram(), length)) {
            if (dependency != targetSlot && !slotInputStaged(stagedInputs, dependency)) {
                return false;
            }
        }
        if (slabProgramUsesHoist(computed.slabProgram())) {
            boolean[] bounds = new boolean[length];
            boolean[] staged = new boolean[]{true};
            markSlotExpressionDependencies(computed.hoistExpression(), bounds, dependency -> {
                if (dependency != targetSlot && !slotInputStaged(stagedInputs, dependency)) {
                    staged[0] = false;
                }
            });
            return staged[0];
        }
        return true;
    }

    private static FinalOutputStageBuild buildComputedSlotStage(
            DfcOpenClNoiseDescriptor descriptor,
            int targetSlot,
            ComputedSlot computed,
            boolean[] stagedInputs,
            int[] slotBufferIndices) {
        if (computedSlotUnrolledSourceLikelyFits(computed)) {
            DfcOpenClGeneratedNoiseSource.BuildResult source =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlanComputedSlotFromSlotBuffer(
                            descriptor, targetSlot, computed, stagedInputs, slotBufferIndices);
            if (source.source().length() <= COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                return FinalOutputStageBuild.generated(targetSlot, source);
            }
        }
        if (computedSlotVmSourceLikelyFits(computed)) {
            DfcOpenClGeneratedNoiseSource.BuildResult source =
                    DfcOpenClGeneratedNoiseSource.buildCompiledPlanComputedSlotVmFromSlotBuffer(
                            descriptor, targetSlot, computed, stagedInputs, slotBufferIndices);
            if (source.source().length() <= COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS) {
                return FinalOutputStageBuild.generated(targetSlot, source);
            }
        }
        if (computedSlotUsesDeviceVmStage(computed)) {
            byte[] lazyProgram = lazySlabProgram(computed.slabProgram());
            return FinalOutputStageBuild.deviceVm(
                    targetSlot, lazyProgram, computed.slabConstants(),
                    slotBufferIndex(slotBufferIndices, targetSlot));
        }
        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanComputedSlotVmFromSlotBuffer(
                        descriptor, targetSlot, computed, stagedInputs, slotBufferIndices);
        return FinalOutputStageBuild.generated(targetSlot, source);
    }

    static boolean computedSlotUnrolledSourceLikelyFits(ComputedSlot computed) {
        if (computed == null || computed.slabProgram() == null) {
            return false;
        }
        long hoistChars = slabProgramUsesHoist(computed.slabProgram()) && computed.hoistExpression() != null
                ? computed.hoistExpression().length()
                : 0L;
        long estimatedChars = 4096L + computed.slabProgram().length * 56L + hoistChars;
        return estimatedChars <= COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS;
    }

    static boolean computedSlotVmSourceLikelyFits(ComputedSlot computed) {
        if (computed == null || computed.slabProgram() == null || computed.slabConstants() == null) {
            return false;
        }
        long hoistChars = slabProgramUsesHoist(computed.slabProgram()) && computed.hoistExpression() != null
                ? computed.hoistExpression().length()
                : 0L;
        long estimatedChars = 4096L
                + computed.slabProgram().length * 8L
                + computed.slabConstants().length * 32L
                + hoistChars;
        return estimatedChars <= COMPILED_PLAN_ALL_WAVES_FUSED_SOURCE_COMPILE_MAX_CHARS;
    }

    static boolean computedSlotUsesDeviceVmStage(ComputedSlot computed) {
        return computed != null
                && computed.slabProgram() != null
                && !slabProgramUsesHoist(computed.slabProgram())
                && !computedSlotVmSourceLikelyFits(computed);
    }

    private static void addFinalOutputStage(DfcOpenClDeviceContext context,
                                            FinalOutputStageBuild build,
                                            List<DfcOpenClDeviceContext.FinalOutputStage> stages,
                                            List<DfcOpenClDeviceContext.GeneratedNoiseKernel> stageKernels) {
        if (build.deviceVm()) {
            stages.add(DfcOpenClDeviceContext.FinalOutputStage.slabVmSlot(
                    build.bytecode(), build.constants(), build.targetSlotBufferIndex()));
            return;
        }
        DfcOpenClDeviceContext.GeneratedNoiseKernel kernel =
                context.compileGeneratedNoiseKernel(build.source().source());
        stageKernels.add(kernel);
        stages.add(DfcOpenClDeviceContext.FinalOutputStage.generated(kernel));
    }

    private static FinalOutputTraceStageInfo[] finalOutputTraceStageInfos(
            OpenClCompiledPlan plan,
            int waveSourceChars,
            List<FinalOutputStageBuild> dependencyStages,
            List<FinalOutputStageBuild> residualStages) {
        List<FinalOutputTraceStageInfo> infos = new ArrayList<>();
        infos.add(new FinalOutputTraceStageInfo("wave", "wave:slotBuffer/src=" + waveSourceChars, false));
        appendFinalOutputTraceStageInfos(infos, plan, dependencyStages, "dep");
        appendFinalOutputTraceStageInfos(infos, plan, residualStages, "root");
        return infos.toArray(new FinalOutputTraceStageInfo[0]);
    }

    private static void appendFinalOutputTraceStageInfos(List<FinalOutputTraceStageInfo> out,
                                                         OpenClCompiledPlan plan,
                                                         List<FinalOutputStageBuild> stages,
                                                         String group) {
        if (stages == null) {
            return;
        }
        for (FinalOutputStageBuild stage : stages) {
            if (stage == null) {
                continue;
            }
            String label = group + ":" + stage.targetSlot() + ":" + finalOutputSlotKind(plan, stage.targetSlot());
            if (stage.targetSlotCount() > 1) {
                label = group + ":" + stage.targetSlot() + "+" + stage.targetSlotCount()
                        + ":" + finalOutputSlotKind(plan, stage.targetSlot());
            }
            if (stage.deviceVm()) {
                int bytecodeLength = stage.bytecode() == null ? 0 : stage.bytecode().length;
                int constantCount = stage.constants() == null ? 0 : stage.constants().length;
                label += "/vm/bc=" + bytecodeLength
                        + "/consts=" + constantCount
                        + "/buf=" + stage.targetSlotBufferIndex();
            } else {
                label += "/gen/src=" + stage.sourceChars();
            }
            out.add(new FinalOutputTraceStageInfo(group, label, stage.deviceVm()));
        }
    }

    static String describeFinalOutputStageTraceTimes(FinalOutputTraceStageInfo[] infos,
                                                     long[] stageNanos,
                                                     long finalKernelNanos,
                                                     long readbackNanos,
                                                     int iterations) {
        int safeIterations = Math.max(1, iterations);
        long waveNanos = 0L;
        long generatedDependencyNanos = 0L;
        long generatedRootNanos = 0L;
        List<String> vmStages = new ArrayList<>();
        List<FinalOutputTraceStageTime> generatedDependencyStages = new ArrayList<>();
        List<FinalOutputTraceStageTime> generatedRootStages = new ArrayList<>();
        int count = Math.min(infos == null ? 0 : infos.length, stageNanos == null ? 0 : stageNanos.length);
        for (int i = 0; i < count; i++) {
            FinalOutputTraceStageInfo info = infos[i];
            long nanos = stageNanos[i];
            if ("wave".equals(info.group())) {
                waveNanos += nanos;
            } else if (info.deviceVm()) {
                vmStages.add(info.label() + "=" + formatMillis(nanos / safeIterations));
            } else if ("dep".equals(info.group())) {
                generatedDependencyNanos += nanos;
                generatedDependencyStages.add(new FinalOutputTraceStageTime(info.label(), nanos));
            } else if ("root".equals(info.group())) {
                generatedRootNanos += nanos;
                generatedRootStages.add(new FinalOutputTraceStageTime(info.label(), nanos));
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("waveMs=").append(formatMillis(waveNanos / safeIterations))
                .append("/generatedDepMs=").append(formatMillis(generatedDependencyNanos / safeIterations))
                .append("/generatedDepTop=")
                .append(describeTopFinalOutputStageTimes(generatedDependencyStages, safeIterations, 8))
                .append("/vmStages=").append(vmStages.size()).append('[');
        for (int i = 0; i < vmStages.size(); i++) {
            if (i > 0) {
                out.append("; ");
            }
            out.append(vmStages.get(i));
        }
        out.append(']')
                .append("/generatedRootMs=").append(formatMillis(generatedRootNanos / safeIterations))
                .append("/generatedRootTop=")
                .append(describeTopFinalOutputStageTimes(generatedRootStages, safeIterations, 8))
                .append("/finalMs=").append(formatMillis(finalKernelNanos / safeIterations))
                .append("/readbackMs=").append(formatMillis(readbackNanos / safeIterations));
        return out.toString();
    }

    private static String describeTopFinalOutputStageTimes(List<FinalOutputTraceStageTime> stages,
                                                           int iterations,
                                                           int limit) {
        if (stages == null || stages.isEmpty()) {
            return "0[]";
        }
        List<FinalOutputTraceStageTime> sorted = new ArrayList<>(stages);
        sorted.sort((left, right) -> Long.compare(right.nanos(), left.nanos()));
        int emitted = Math.min(Math.max(0, limit), sorted.size());
        StringBuilder out = new StringBuilder();
        out.append(sorted.size()).append('[');
        for (int i = 0; i < emitted; i++) {
            if (i > 0) {
                out.append("; ");
            }
            FinalOutputTraceStageTime stage = sorted.get(i);
            out.append(stage.label())
                    .append('=')
                    .append(formatMillis(stage.nanos() / Math.max(1, iterations)));
        }
        if (emitted < sorted.size()) {
            if (emitted > 0) {
                out.append("; ");
            }
            out.append('+').append(sorted.size() - emitted);
        }
        out.append(']');
        return out.toString();
    }

    static String describeFinalOutputExternalPrefillTrace(OpenClCompiledPlan plan,
                                                          boolean[] inputSlots,
                                                          FinalOutputExternalPrefillTrace trace,
                                                          int limit) {
        if (trace == null) {
            return "none";
        }
        List<FinalOutputExternalPrefillSlotTime> slotTimes = new ArrayList<>();
        long slotNanos = 0L;
        long[] traceSlotNanos = trace.slotNanos() == null ? new long[0] : trace.slotNanos();
        int[] traceSlotValues = trace.slotValues() == null ? new int[0] : trace.slotValues();
        int count = Math.min(inputSlots == null ? 0 : inputSlots.length, traceSlotNanos.length);
        for (int slot = 0; slot < count; slot++) {
            if (!inputSlots[slot]) {
                continue;
            }
            long nanos = traceSlotNanos[slot];
            int values = slot < traceSlotValues.length ? traceSlotValues[slot] : 0;
            slotNanos += nanos;
            slotTimes.add(new FinalOutputExternalPrefillSlotTime(
                    slot, slot + ":" + finalOutputSlotKind(plan, slot), nanos, values));
        }
        long accountedNanos = trace.allocateNanos() + trace.setupNanos() + trace.scanNanos() + slotNanos;
        long otherNanos = Math.max(0L, trace.totalNanos() - accountedNanos);
        return "totalMs=" + formatMillis(trace.totalNanos())
                + "/allocMs=" + formatMillis(trace.allocateNanos())
                + "/setupMs=" + formatMillis(trace.setupNanos())
                + "/scanMs=" + formatMillis(trace.scanNanos())
                + "/slotMs=" + formatMillis(slotNanos)
                + "/otherMs=" + formatMillis(otherNanos)
                + "/slotTop=" + describeTopExternalPrefillSlotTimes(slotTimes, limit);
    }

    private static String describeTopExternalPrefillSlotTimes(List<FinalOutputExternalPrefillSlotTime> slots,
                                                              int limit) {
        if (slots == null || slots.isEmpty()) {
            return "0[]";
        }
        List<FinalOutputExternalPrefillSlotTime> sorted = new ArrayList<>(slots);
        sorted.sort((left, right) -> Long.compare(right.nanos(), left.nanos()));
        int emitted = Math.min(Math.max(0, limit), sorted.size());
        StringBuilder out = new StringBuilder();
        out.append(sorted.size()).append('[');
        for (int i = 0; i < emitted; i++) {
            if (i > 0) {
                out.append("; ");
            }
            FinalOutputExternalPrefillSlotTime slot = sorted.get(i);
            out.append(slot.label())
                    .append('=')
                    .append(formatMillis(slot.nanos()))
                    .append('/')
                    .append(slot.values());
        }
        if (emitted < sorted.size()) {
            if (emitted > 0) {
                out.append("; ");
            }
            out.append('+').append(sorted.size() - emitted);
        }
        out.append(']');
        return out.toString();
    }

    private static int countDeviceVmStages(DfcOpenClDeviceContext.FinalOutputStage[] stages) {
        int count = 0;
        if (stages != null) {
            for (DfcOpenClDeviceContext.FinalOutputStage stage : stages) {
                if (stage != null && !stage.generated()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String describeDeviceVmStageBuilds(OpenClCompiledPlan plan,
                                                      List<FinalOutputStageBuild> dependencyStages,
                                                      List<FinalOutputStageBuild> residualStages,
                                                      int limit) {
        int count = countDeviceVmStageBuilds(dependencyStages) + countDeviceVmStageBuilds(residualStages);
        StringBuilder out = new StringBuilder();
        out.append(count).append('[');
        int emitted = appendDeviceVmStageBuilds(out, plan, dependencyStages, "dep", 0, count, limit);
        appendDeviceVmStageBuilds(out, plan, residualStages, "root", emitted, count, limit);
        out.append(']');
        return out.toString();
    }

    private static int appendDeviceVmStageBuilds(StringBuilder out,
                                                 OpenClCompiledPlan plan,
                                                 List<FinalOutputStageBuild> stages,
                                                 String group,
                                                 int emitted,
                                                 int count,
                                                 int limit) {
        if (stages == null) {
            return emitted;
        }
        int safeLimit = Math.max(0, limit);
        for (FinalOutputStageBuild stage : stages) {
            if (stage == null || !stage.deviceVm()) {
                continue;
            }
            if (emitted > 0) {
                out.append("; ");
            }
            if (emitted >= safeLimit) {
                out.append('+').append(count - emitted);
                return count;
            }
            int bytecodeLength = stage.bytecode() == null ? 0 : stage.bytecode().length;
            int constantCount = stage.constants() == null ? 0 : stage.constants().length;
            out.append(group)
                    .append(':')
                    .append(stage.targetSlot())
                    .append(':')
                    .append(finalOutputSlotKind(plan, stage.targetSlot()))
                    .append("/bc=")
                    .append(bytecodeLength)
                    .append("/consts=")
                    .append(constantCount)
                    .append("/lazy=")
                    .append(slabProgramUsesLazyBranch(stage.bytecode()))
                    .append("/buf=")
                    .append(stage.targetSlotBufferIndex());
            emitted++;
        }
        return emitted;
    }

    private static int countDeviceVmStageBuilds(List<FinalOutputStageBuild> stages) {
        int count = 0;
        if (stages != null) {
            for (FinalOutputStageBuild stage : stages) {
                if (stage != null && stage.deviceVm()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean slotInputStaged(boolean[] stagedInputs, int slot) {
        return stagedInputs != null && slot >= 0 && slot < stagedInputs.length && stagedInputs[slot];
    }

    static boolean[] compiledPlanFinalOutputCpuInputSlots(OpenClCompiledPlan plan,
                                                          boolean[] scheduledSlots,
                                                          boolean[] residualGpuInputSlots,
                                                          boolean[] residualDependencyCpuSlots,
                                                          int slotCount) {
        int length = Math.max(0, slotCount);
        boolean[] inputs = compiledPlanFinalOutputExternalInputs(plan, length);
        if (residualDependencyCpuSlots != null) {
            for (int slot = 0; slot < Math.min(length, residualDependencyCpuSlots.length); slot++) {
                if (residualDependencyCpuSlots[slot]) {
                    inputs[slot] = true;
                }
            }
        }
        boolean[] roots = compiledPlanFinalOutputRootSlots(plan, length);
        for (int slot = 0; slot < length; slot++) {
            boolean scheduled = scheduledSlots != null && slot < scheduledSlots.length && scheduledSlots[slot];
            boolean gpuResidual = residualGpuInputSlots != null
                    && slot < residualGpuInputSlots.length
                    && residualGpuInputSlots[slot];
            if (roots[slot] && !scheduled && !gpuResidual) {
                inputs[slot] = true;
            }
        }
        return inputs;
    }

    static boolean[] compiledPlanFinalOutputExternalInputs(OpenClCompiledPlan plan, int slotCount) {
        int length = Math.max(0, slotCount);
        boolean[] inputs = new boolean[length];
        boolean[] visited = new boolean[length];
        boolean[] visiting = new boolean[length];
        boolean[] roots = compiledPlanFinalOutputRootSlots(plan, length);
        for (int slot = 0; slot < roots.length; slot++) {
            if (roots[slot]) {
                collectFinalOutputExternalInputs(plan, slot, inputs, visited, visiting);
            }
        }
        return inputs;
    }

    static ComputedSlot[] compiledPlanFinalOutputComputedSlots(OpenClCompiledPlan plan, int slotCount) {
        ComputedSlot[] computedSlots = plan.computedSlots();
        if (computedSlots == null) {
            return null;
        }
        return Arrays.copyOf(computedSlots, Math.max(0, slotCount));
    }

    private static void collectFinalOutputExternalInputs(OpenClCompiledPlan plan,
                                                         int slot,
                                                         boolean[] inputs,
                                                         boolean[] visited,
                                                         boolean[] visiting) {
        if (slot < 0 || slot >= inputs.length) {
            return;
        }
        if (isExternalSlot(plan.externalSlots(), slot)) {
            inputs[slot] = true;
            return;
        }
        if (visited[slot]) {
            return;
        }
        if (visiting[slot]) {
            throw new IllegalStateException("cyclic compiled final output dependency at slot " + slot);
        }
        visiting[slot] = true;
        try {
            ComputedSlot computed = computedSlot(plan.computedSlots(), slot);
            if (computed != null) {
                for (int dependency : slotDependencies(computed.slabProgram(), inputs.length)) {
                    collectFinalOutputExternalInputs(plan, dependency, inputs, visited, visiting);
                }
                if (slabProgramUsesHoist(computed.slabProgram())) {
                    markSlotExpressionDependencies(computed.hoistExpression(), inputs, dependency -> {
                        collectFinalOutputExternalInputs(plan, dependency, inputs, visited, visiting);
                    });
                }
            } else {
                collectFinalOutputSlotCoordinateInputs(plan, slot, inputs, visited, visiting);
            }
        } finally {
            visiting[slot] = false;
        }
        visited[slot] = true;
    }

    private static void collectFinalOutputSlotCoordinateInputs(OpenClCompiledPlan plan,
                                                               int slot,
                                                               boolean[] inputs,
                                                               boolean[] visited,
                                                               boolean[] visiting) {
        collectFinalOutputExpressionInputs(expressionAt(plan.slotCoordXExpressions(), slot),
                plan, inputs, visited, visiting);
        collectFinalOutputExpressionInputs(expressionAt(plan.slotCoordYExpressions(), slot),
                plan, inputs, visited, visiting);
        collectFinalOutputExpressionInputs(expressionAt(plan.slotCoordZExpressions(), slot),
                plan, inputs, visited, visiting);
    }

    private static void collectFinalOutputExpressionInputs(String expression,
                                                           OpenClCompiledPlan plan,
                                                           boolean[] inputs,
                                                           boolean[] visited,
                                                           boolean[] visiting) {
        markSlotExpressionDependencies(expression, inputs, dependency -> {
            collectFinalOutputExternalInputs(plan, dependency, inputs, visited, visiting);
        });
    }

    private static boolean[] waveExternalInputs(OpenClCompiledPlan plan, boolean[] wave,
                                                int[] chunkStartSlots, int[] chunkEndSlots,
                                                boolean[] targetSlots) {
        int slotCount = plan.specs() == null ? 0 : plan.specs().length;
        boolean[] inputs = new boolean[slotCount];
        if (wave == null) {
            return inputs;
        }
        int limit = Math.min(Math.min(chunkStartSlots.length, chunkEndSlots.length), wave.length);
        for (int chunk = 0; chunk < limit; chunk++) {
            if (!wave[chunk]) {
                continue;
            }
            boolean[] chunkInputs = compiledPlanChunkExternalInputs(
                    plan, chunkStartSlots[chunk], chunkEndSlots[chunk]);
            int inputLimit = Math.min(inputs.length, chunkInputs.length);
            for (int slot = 0; slot < inputLimit; slot++) {
                inputs[slot] |= chunkInputs[slot];
            }
        }
        int targetLimit = Math.min(inputs.length, targetSlots == null ? 0 : targetSlots.length);
        for (int slot = 0; slot < targetLimit; slot++) {
            if (targetSlots[slot]) {
                inputs[slot] = false;
            }
        }
        return inputs;
    }

    private static ComputedSlot[] waveComputedSlots(OpenClCompiledPlan plan, boolean[] targetSlots) {
        ComputedSlot[] computedSlots = plan.computedSlots();
        if (computedSlots == null) {
            return null;
        }
        ComputedSlot[] waveComputedSlots = Arrays.copyOf(computedSlots, computedSlots.length);
        for (int slot = 0; slot < waveComputedSlots.length; slot++) {
            if (targetSlots == null || slot >= targetSlots.length || !targetSlots[slot]) {
                waveComputedSlots[slot] = null;
            }
        }
        return waveComputedSlots;
    }

    private static boolean[][] identityWaves(int count) {
        boolean[][] waves = new boolean[Math.max(0, count)][Math.max(0, count)];
        for (int i = 0; i < waves.length; i++) {
            waves[i][i] = true;
        }
        return waves;
    }

    private static void collectRuntimeChunks(OpenClCompiledPlan plan, List<RuntimeChunk> chunks) {
        int slots = plan.specs() == null ? 0 : plan.specs().length;
        int start = -1;
        int count = 0;
        int octaves = 0;
        int computed = 0;
        for (int slot = 0; slot < slots; slot++) {
            int slotOctaves = runtimeSlotOctaves(plan, slot);
            int slotComputed = computedSlot(plan.computedSlots(), slot) == null ? 0 : 1;
            if (runtimeSlotChunkBlocked(plan, slot, slotOctaves, slotComputed)) {
                if (count > 0) {
                    chunks.add(new RuntimeChunk(start, slot - 1, count, octaves, computed));
                    start = -1;
                    count = 0;
                    octaves = 0;
                    computed = 0;
                }
                continue;
            }
            if (count > 0
                    && (count + 1 > RUNTIME_FINAL_CHUNK_MAX_SLOTS
                    || octaves + slotOctaves > RUNTIME_FINAL_CHUNK_MAX_OCTAVES
                    || computed + slotComputed > RUNTIME_FINAL_CHUNK_MAX_COMPUTED)) {
                chunks.add(new RuntimeChunk(start, slot - 1, count, octaves, computed));
                start = -1;
                count = 0;
                octaves = 0;
                computed = 0;
            }
            if (count == 0) {
                start = slot;
            }
            count++;
            octaves += slotOctaves;
            computed += slotComputed;
        }
        if (count > 0) {
            chunks.add(new RuntimeChunk(start, slots - 1, count, octaves, computed));
        }
    }

    private static boolean runtimeSlotChunkBlocked(OpenClCompiledPlan plan, int slot, int octaves, int computed) {
        return octaves > RUNTIME_FINAL_CHUNK_MAX_OCTAVES
                || computed > RUNTIME_FINAL_CHUNK_MAX_COMPUTED
                || isExternalSlot(plan.externalSlots(), slot);
    }

    private static int runtimeSlotOctaves(OpenClCompiledPlan plan, int slot) {
        int total = 0;
        NoiseSpec[] specs = plan.specs();
        if (specs != null && slot >= 0 && slot < specs.length && specs[slot] != null) {
            total += specs[slot].totalActiveOctaves();
        }
        BlendedNoiseSpec[] blendedSpecs = plan.blendedSpecs();
        if (blendedSpecs != null && slot >= 0 && slot < blendedSpecs.length) {
            total += runtimeBlendedOctaves(blendedSpecs[slot]);
        }
        return total;
    }

    private static int runtimeBlendedOctaves(BlendedNoiseSpec spec) {
        if (spec == null) {
            return 0;
        }
        return countNonNull(spec.mainOctaves())
                + countNonNull(spec.minLimitOctaves())
                + countNonNull(spec.maxLimitOctaves());
    }

    private static int countNonNull(Object[] values) {
        int count = 0;
        if (values != null) {
            for (Object value : values) {
                if (value != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int[] runtimeChunkSlotOwners(List<RuntimeChunk> chunks, int slots) {
        int[] owners = new int[Math.max(0, slots)];
        Arrays.fill(owners, -1);
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            RuntimeChunk chunk = chunks.get(chunkIndex);
            int start = Math.max(0, chunk.startSlot());
            int end = Math.min(owners.length - 1, chunk.endSlot());
            for (int slot = start; slot <= end; slot++) {
                owners[slot] = chunkIndex;
            }
        }
        return owners;
    }

    private static RuntimeWavePlan collectRuntimeChunkWaves(List<boolean[]> chunkInputs, int[] slotOwners) {
        int chunkCount = chunkInputs == null ? 0 : chunkInputs.size();
        boolean[] scheduledChunks = new boolean[chunkCount];
        boolean[] directBlockedChunks = new boolean[chunkCount];
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            directBlockedChunks[chunk] = runtimeBlockedInputCount(chunkInputs.get(chunk), slotOwners) > 0;
        }

        List<boolean[]> waves = new ArrayList<>();
        while (true) {
            boolean[] wave = new boolean[chunkCount];
            int waveChunks = 0;
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                if (scheduledChunks[chunk] || directBlockedChunks[chunk]) {
                    continue;
                }
                if (runtimeChunkInputsReady(chunkInputs.get(chunk), slotOwners, scheduledChunks)) {
                    wave[chunk] = true;
                    waveChunks++;
                }
            }
            if (waveChunks == 0) {
                break;
            }
            waves.add(wave);
            for (int chunk = 0; chunk < wave.length; chunk++) {
                scheduledChunks[chunk] |= wave[chunk];
            }
        }
        return new RuntimeWavePlan(waves.toArray(new boolean[0][]), scheduledChunks, directBlockedChunks);
    }

    private static boolean runtimeChunkInputsReady(boolean[] inputs, int[] slotOwners, boolean[] scheduledChunks) {
        if (inputs == null) {
            return true;
        }
        for (int slot = 0; slot < inputs.length; slot++) {
            if (!inputs[slot]) {
                continue;
            }
            int owner = runtimeSlotOwner(slotOwners, slot);
            if (owner < 0 || owner >= scheduledChunks.length || !scheduledChunks[owner]) {
                return false;
            }
        }
        return true;
    }

    private static int runtimeBlockedInputCount(boolean[] inputs, int[] slotOwners) {
        int count = 0;
        if (inputs != null) {
            for (int slot = 0; slot < inputs.length; slot++) {
                if (inputs[slot] && runtimeSlotOwner(slotOwners, slot) < 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int runtimeSlotOwner(int[] slotOwners, int slot) {
        if (slotOwners == null || slot < 0 || slot >= slotOwners.length) {
            return -1;
        }
        return slotOwners[slot];
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        if (values != null) {
            for (boolean value : values) {
                if (value) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int externalSlotCount(boolean[] externalSlots) {
        int count = 0;
        if (externalSlots != null) {
            for (boolean externalSlot : externalSlots) {
                if (externalSlot) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int externalSlotCount(boolean[] externalSlots, int usedSlotCount) {
        int count = 0;
        int limit = Math.max(0, usedSlotCount);
        if (externalSlots != null) {
            for (int slot = 0; slot < Math.min(externalSlots.length, limit); slot++) {
                if (externalSlots[slot]) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean[] inactiveSlots(OpenClCompiledPlan plan) {
        int length = plan.specs() == null ? 0 : plan.specs().length;
        boolean[] inactive = new boolean[length];
        boolean[] externalSlots = plan.externalSlots();
        ComputedSlot[] computedSlots = plan.computedSlots();
        for (int slot = 0; slot < length; slot++) {
            inactive[slot] = isExternalSlot(externalSlots, slot) || computedSlot(computedSlots, slot) != null;
        }
        return inactive;
    }

    public static boolean[] compiledPlanChunkExternalInputs(OpenClCompiledPlan plan, int startSlot, int endSlot) {
        int length = plan.specs() == null ? 0 : plan.specs().length;
        boolean[] inputs = new boolean[length];
        boolean[] visited = new boolean[length];
        boolean[] visiting = new boolean[length];
        int safeStart = Math.max(0, Math.min(startSlot, Math.max(0, length - 1)));
        int safeEnd = Math.max(safeStart, Math.min(endSlot, Math.max(0, length - 1)));
        for (int slot = safeStart; slot <= safeEnd; slot++) {
            collectChunkExternalInputs(plan, slot, safeStart, safeEnd, inputs, visited, visiting);
        }
        return inputs;
    }

    private static void collectChunkExternalInputs(OpenClCompiledPlan plan, int slot, int startSlot, int endSlot,
                                                   boolean[] inputs, boolean[] visited, boolean[] visiting) {
        if (slot < 0 || slot >= inputs.length) {
            return;
        }
        boolean[] externalSlots = plan.externalSlots();
        if (slot < startSlot || slot > endSlot || isExternalSlot(externalSlots, slot)) {
            inputs[slot] = true;
            return;
        }
        if (visited[slot]) {
            return;
        }
        if (visiting[slot]) {
            throw new IllegalStateException("cyclic compiled chunk dependency at slot " + slot);
        }
        visiting[slot] = true;
        try {
            ComputedSlot computed = computedSlot(plan.computedSlots(), slot);
            if (computed != null) {
                for (int dependency : slotDependencies(computed.slabProgram(), inputs.length)) {
                    collectChunkExternalInputs(plan, dependency, startSlot, endSlot, inputs, visited, visiting);
                }
                if (slabProgramUsesHoist(computed.slabProgram())) {
                    markSlotExpressionDependencies(computed.hoistExpression(), inputs, dependency -> {
                        collectChunkExternalInputs(plan, dependency, startSlot, endSlot, inputs, visited, visiting);
                    });
                }
            } else {
                collectChunkSlotCoordinateInputs(plan, slot, startSlot, endSlot, inputs, visited, visiting);
            }
        } finally {
            visiting[slot] = false;
        }
        visited[slot] = true;
    }

    private static void collectChunkSlotCoordinateInputs(OpenClCompiledPlan plan, int slot, int startSlot, int endSlot,
                                                        boolean[] inputs, boolean[] visited, boolean[] visiting) {
        collectChunkExpressionInputs(expressionAt(plan.slotCoordXExpressions(), slot),
                plan, startSlot, endSlot, inputs, visited, visiting);
        collectChunkExpressionInputs(expressionAt(plan.slotCoordYExpressions(), slot),
                plan, startSlot, endSlot, inputs, visited, visiting);
        collectChunkExpressionInputs(expressionAt(plan.slotCoordZExpressions(), slot),
                plan, startSlot, endSlot, inputs, visited, visiting);
    }

    private static void collectChunkExpressionInputs(String expression, OpenClCompiledPlan plan,
                                                     int startSlot, int endSlot,
                                                     boolean[] inputs, boolean[] visited, boolean[] visiting) {
        markSlotExpressionDependencies(expression, inputs, dependency -> {
            collectChunkExternalInputs(plan, dependency, startSlot, endSlot, inputs, visited, visiting);
        });
    }

    private static ComputedSlot[] chunkComputedSlots(OpenClCompiledPlan plan, int startSlot, int endSlot) {
        ComputedSlot[] computedSlots = plan.computedSlots();
        if (computedSlots == null) {
            return null;
        }
        ComputedSlot[] chunkComputedSlots = Arrays.copyOf(computedSlots, computedSlots.length);
        for (int slot = 0; slot < chunkComputedSlots.length; slot++) {
            if (slot < startSlot || slot > endSlot) {
                chunkComputedSlots[slot] = null;
            }
        }
        return chunkComputedSlots;
    }

    private static ChunkDescriptorInput chunkDescriptorInput(OpenClCompiledPlan plan, int startSlot, int endSlot) {
        int length = plan.specs() == null ? 0 : plan.specs().length;
        NoiseSpec[] specs = Arrays.copyOf(plan.specs(), length);
        BlendedNoiseSpec[] blendedSpecs = plan.blendedSpecs() == null
                ? null
                : Arrays.copyOf(plan.blendedSpecs(), length);
        boolean[] inactive = new boolean[length];
        boolean[] externalSlots = plan.externalSlots();
        ComputedSlot[] computedSlots = plan.computedSlots();
        for (int slot = 0; slot < length; slot++) {
            boolean slotInactive = slot < startSlot || slot > endSlot
                    || isExternalSlot(externalSlots, slot)
                    || computedSlot(computedSlots, slot) != null;
            inactive[slot] = slotInactive;
            if (slotInactive) {
                specs[slot] = null;
                if (blendedSpecs != null) {
                    blendedSpecs[slot] = null;
                }
            }
        }
        return new ChunkDescriptorInput(specs, blendedSpecs, inactive);
    }

    private static boolean isExternalSlot(boolean[] externalSlots, int slot) {
        return externalSlots != null && slot >= 0 && slot < externalSlots.length && externalSlots[slot];
    }

    private static ComputedSlot computedSlot(ComputedSlot[] computedSlots, int slot) {
        return computedSlots != null && slot >= 0 && slot < computedSlots.length ? computedSlots[slot] : null;
    }

    private static int computedSlotCount(ComputedSlot[] computedSlots, int usedSlotCount) {
        int count = 0;
        int limit = Math.max(0, usedSlotCount);
        if (computedSlots != null) {
            for (int slot = 0; slot < Math.min(computedSlots.length, limit); slot++) {
                if (computedSlots[slot] != null) {
                    count++;
                }
            }
        }
        return count;
    }

    static byte[] lazySlabProgram(byte[] program) {
        if (program == null || program.length == 0 || !slabProgramUsesRangeChoice(program)) {
            return program;
        }
        LazySlabNode root = parseLazySlabProgram(program);
        LazyBytecodeWriter out = new LazyBytecodeWriter(program.length + Math.min(program.length, 4096));
        root.emitLazy(out);
        return out.toByteArray();
    }

    static boolean slabProgramUsesLazyBranch(byte[] program) {
        if (program == null) {
            return false;
        }
        for (int pc = 0; pc < program.length;) {
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST, OP_COND_NEG_SCALE -> pc += 2;
                case OP_PUSH_SLOT -> pc++;
                case OP_Y_CLAMPED_GRADIENT -> pc += 8;
                case OP_RANGE_CHOICE -> pc += 4;
                case OP_RANGE_CHOICE_JUMP -> {
                    return true;
                }
                case OP_JUMP -> {
                    return true;
                }
                case OP_BLOCK_X, OP_BLOCK_Y, OP_BLOCK_Z, OP_HOIST,
                     OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX,
                     OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                }
                default -> throw new IllegalStateException("unsupported compiled plan opcode " + op);
            }
        }
        return false;
    }

    private static boolean slabProgramUsesRangeChoice(byte[] program) {
        if (program == null) {
            return false;
        }
        for (int pc = 0; pc < program.length;) {
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST, OP_COND_NEG_SCALE -> pc += 2;
                case OP_PUSH_SLOT -> pc++;
                case OP_Y_CLAMPED_GRADIENT -> pc += 8;
                case OP_RANGE_CHOICE -> {
                    return true;
                }
                case OP_BLOCK_X, OP_BLOCK_Y, OP_BLOCK_Z, OP_HOIST,
                     OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX,
                     OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                }
                default -> throw new IllegalStateException("unsupported compiled plan opcode " + op);
            }
        }
        return false;
    }

    private static LazySlabNode parseLazySlabProgram(byte[] program) {
        List<LazySlabNode> stack = new ArrayList<>();
        for (int pc = 0; pc < program.length;) {
            int start = pc;
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST -> {
                    pc += 2;
                    stack.add(new LazyRawNode(Arrays.copyOfRange(program, start, pc)));
                }
                case OP_PUSH_SLOT -> {
                    pc++;
                    stack.add(new LazyRawNode(Arrays.copyOfRange(program, start, pc)));
                }
                case OP_COND_NEG_SCALE -> {
                    pc += 2;
                    stack.add(new LazyUnaryNode(popLazyNode(stack), Arrays.copyOfRange(program, start, pc)));
                }
                case OP_Y_CLAMPED_GRADIENT -> {
                    pc += 8;
                    stack.add(new LazyRawNode(Arrays.copyOfRange(program, start, pc)));
                }
                case OP_RANGE_CHOICE -> {
                    int min = readU16(program, pc);
                    pc += 2;
                    int max = readU16(program, pc);
                    pc += 2;
                    LazySlabNode whenOut = popLazyNode(stack);
                    LazySlabNode whenIn = popLazyNode(stack);
                    LazySlabNode input = popLazyNode(stack);
                    stack.add(new LazyRangeNode(input, whenIn, whenOut, min, max));
                }
                case OP_BLOCK_X, OP_BLOCK_Y, OP_BLOCK_Z, OP_HOIST -> {
                    stack.add(new LazyRawNode(Arrays.copyOfRange(program, start, pc)));
                }
                case OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX -> {
                    LazySlabNode right = popLazyNode(stack);
                    LazySlabNode left = popLazyNode(stack);
                    stack.add(new LazyBinaryNode(left, right, op));
                }
                case OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                    stack.add(new LazyUnaryNode(popLazyNode(stack), new byte[]{(byte) op}));
                }
                default -> throw new IllegalStateException("unsupported compiled plan opcode " + op);
            }
        }
        if (stack.size() != 1) {
            throw new IllegalStateException("compiled plan program ended with stack depth " + stack.size());
        }
        return stack.get(0);
    }

    private static LazySlabNode popLazyNode(List<LazySlabNode> stack) {
        if (stack.isEmpty()) {
            throw new IllegalStateException("compiled plan program underflow");
        }
        return stack.remove(stack.size() - 1);
    }

    static int[] slotDependencies(byte[] program, int slotCount) {
        boolean[] seen = new boolean[slotCount];
        int count = 0;
        for (int pc = 0; pc < program.length;) {
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST, OP_COND_NEG_SCALE -> pc += 2;
                case OP_PUSH_SLOT -> {
                    int slot = program[pc++] & 0xFF;
                    if (slot < 0 || slot >= slotCount) {
                        throw new IllegalStateException("compiled slab program references missing slot " + slot);
                    }
                    if (!seen[slot]) {
                        seen[slot] = true;
                        count++;
                    }
                }
                case OP_Y_CLAMPED_GRADIENT -> pc += 8;
                case OP_RANGE_CHOICE -> pc += 4;
                case OP_RANGE_CHOICE_JUMP -> pc += 12;
                case OP_JUMP -> pc += 4;
                case OP_BLOCK_X, OP_BLOCK_Y, OP_BLOCK_Z, OP_HOIST,
                     OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX,
                     OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                }
                default -> throw new IllegalStateException("unsupported compiled plan opcode " + op);
            }
        }
        int[] dependencies = new int[count];
        int next = 0;
        for (int slot = 0; slot < seen.length; slot++) {
            if (seen[slot]) {
                dependencies[next++] = slot;
            }
        }
        return dependencies;
    }

    static boolean slabProgramUsesHoist(byte[] program) {
        if (program == null) {
            return false;
        }
        for (int pc = 0; pc < program.length;) {
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST, OP_COND_NEG_SCALE -> pc += 2;
                case OP_PUSH_SLOT -> pc++;
                case OP_Y_CLAMPED_GRADIENT -> pc += 8;
                case OP_RANGE_CHOICE -> pc += 4;
                case OP_RANGE_CHOICE_JUMP -> pc += 12;
                case OP_JUMP -> pc += 4;
                case OP_HOIST -> {
                    return true;
                }
                case OP_BLOCK_X, OP_BLOCK_Y, OP_BLOCK_Z,
                     OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX,
                     OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                }
                default -> throw new IllegalStateException("unsupported compiled plan opcode " + op);
            }
        }
        return false;
    }

    private static String expressionAt(String[] expressions, int slot) {
        return expressions != null && slot >= 0 && slot < expressions.length ? expressions[slot] : null;
    }

    private static void markSlotExpressionDependencies(String expression, boolean[] bounds, IntConsumer dependency) {
        if (expression == null || expression.indexOf("slot") < 0) {
            return;
        }
        for (int i = 0; i < expression.length() - 4; i++) {
            if (expression.charAt(i) != 's'
                    || expression.charAt(i + 1) != 'l'
                    || expression.charAt(i + 2) != 'o'
                    || expression.charAt(i + 3) != 't') {
                continue;
            }
            int digit = i + 4;
            if (digit >= expression.length() || !Character.isDigit(expression.charAt(digit))) {
                continue;
            }
            int value = 0;
            int end = digit;
            while (end < expression.length() && Character.isDigit(expression.charAt(end))) {
                value = value * 10 + (expression.charAt(end) - '0');
                end++;
            }
            if (value >= 0 && value < bounds.length) {
                dependency.accept(value);
            }
            i = end;
        }
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String formatDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.6g", value);
    }

    private static String formatNanosPerValue(long nanos, long values) {
        double perValue = values <= 0L ? 0.0D : nanos / (double) values;
        return String.format(java.util.Locale.ROOT, "%.1f", perValue);
    }

    private static int elementSlotIndex(int element, int slotCount, int slot) {
        return element * slotCount + slot;
    }

    private static int readU16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int readI32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static double cellBlockX(int element, DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request) {
        int cellVolume = request.cellWidth() * request.cellWidth() * request.cellHeight();
        int cell = element / cellVolume;
        int inCell = element - cell * cellVolume;
        int plane = inCell % (request.cellWidth() * request.cellWidth());
        int ix = plane / request.cellWidth();
        int cellX = cell & 31;
        return request.firstBlockX() + cellX * request.cellWidth() + ix;
    }

    private static double cellBlockY(int element, DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request) {
        int planeSize = request.cellWidth() * request.cellWidth();
        int inCell = element % (planeSize * request.cellHeight());
        int yIndex = inCell / planeSize;
        return request.firstBlockY() + (request.cellHeight() - 1 - yIndex);
    }

    private static double cellBlockZ(int element, DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request) {
        int cellVolume = request.cellWidth() * request.cellWidth() * request.cellHeight();
        int cell = element / cellVolume;
        int inCell = element - cell * cellVolume;
        int plane = inCell % (request.cellWidth() * request.cellWidth());
        int iz = plane % request.cellWidth();
        int cellZ = cell >> 5;
        return request.firstBlockZ() + cellZ * request.cellWidth() + iz;
    }

    private static double clampedMap(double value, double oldMin, double oldMax, double newMin, double newMax) {
        double delta = (value - oldMin) / (oldMax - oldMin);
        if (delta < 0.0D) {
            return newMin;
        }
        if (delta > 1.0D) {
            return newMax;
        }
        return newMin + delta * (newMax - newMin);
    }

    private static double squeeze(double value) {
        double clamped = Math.max(-1.0D, Math.min(1.0D, value));
        return clamped / 2.0D - clamped * clamped * clamped / 24.0D;
    }

    private static String wrapModeLabel(DfcOpenClGeneratedNoiseSource.WrapMode wrapMode) {
        return switch (wrapMode) {
            case WRAP -> "true";
            case NOWRAP -> "false";
        };
    }

    private static boolean noWrapAxisSafe(DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request, int usedSlots) {
        int safeSlots = Math.min(Math.max(0, usedSlots), request.slotCount());
        int branchesPerSlot = Math.max(0, request.branchesPerSlot());
        int branchLimit = Math.min(safeSlots * branchesPerSlot, Math.min(request.branchCoordScales().length,
                Math.min(request.branchOctaveOffsets().length, request.branchOctaveCounts().length)));
        if (safeSlots <= 0 || branchesPerSlot <= 0 || branchLimit <= 0) {
            return false;
        }

        int maxCellX = Math.min(Math.max(0, request.cells() - 1), 31);
        int maxCellZ = Math.max(0, request.cells() - 1) >> 5;
        double maxBx = request.firstBlockX() + (double) maxCellX * request.cellWidth()
                + (request.cellWidth() - 1.0D);
        double maxBy = request.firstBlockY() + (request.cellHeight() - 1.0D);
        double maxBz = request.firstBlockZ() + (double) maxCellZ * request.cellWidth()
                + (request.cellWidth() - 1.0D);
        double maxAbsBx = Math.max(Math.abs(request.firstBlockX()), Math.abs(maxBx));
        double maxAbsBy = Math.max(Math.abs(request.firstBlockY()), Math.abs(maxBy));
        double maxAbsBz = Math.max(Math.abs(request.firstBlockZ()), Math.abs(maxBz));
        if (!Double.isFinite(maxAbsBx) || !Double.isFinite(maxAbsBy) || !Double.isFinite(maxAbsBz)) {
            return false;
        }

        for (int branch = 0; branch < branchLimit; branch++) {
            int octaveOffset = request.branchOctaveOffsets()[branch];
            int octaveCount = request.branchOctaveCounts()[branch];
            double coordScale = request.branchCoordScales()[branch];
            if (!Double.isFinite(coordScale) || octaveOffset < 0 || octaveCount < 0) {
                return false;
            }
            int octaveEnd = octaveOffset + octaveCount;
            if (octaveEnd < octaveOffset || octaveEnd > request.inputFactors().length) {
                return false;
            }
            for (int octave = octaveOffset; octave < octaveEnd; octave++) {
                double inputScale = Math.abs(coordScale * request.inputFactors()[octave]);
                if (!Double.isFinite(inputScale)
                        || maxAbsBx * inputScale >= WRAP_AXIS_FAST_LIMIT
                        || maxAbsBy * inputScale >= WRAP_AXIS_FAST_LIMIT
                        || maxAbsBz * inputScale >= WRAP_AXIS_FAST_LIMIT) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int kernelNoiseOctaves(DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request, int usedSlots) {
        int safeSlots = Math.min(Math.max(0, usedSlots), request.slotCount());
        int branches = Math.max(0, request.branchesPerSlot());
        int limit = Math.min(request.branchOctaveCounts().length, safeSlots * branches);
        int total = 0;
        for (int branch = 0; branch < limit; branch++) {
            total += Math.max(0, request.branchOctaveCounts()[branch]);
        }
        return total;
    }

    public record SlabVmSelfTest(
            boolean passed,
            DfcOpenClDeviceInfo device,
            long elapsedNanos,
            String message) {

        private static SlabVmSelfTest failed(DfcOpenClDeviceInfo device, String message) {
            return new SlabVmSelfTest(false, device, 0L, message);
        }
    }

    public record SlabVmCoordBenchmark(
            boolean passed,
            DfcOpenClDeviceInfo device,
            int repeats,
            int iterations,
            int warmups,
            int elementsPerIteration,
            long totalElements,
            long totalNanos,
            long averageNanos,
            long bestNanos,
            long worstNanos,
            String message) {

        private static SlabVmCoordBenchmark failed(DfcOpenClDeviceInfo device, String message) {
            return new SlabVmCoordBenchmark(false, device, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, message);
        }
    }

    public record SlabVmCellBenchmark(
            boolean passed,
            DfcOpenClDeviceInfo device,
            int cellWidth,
            int cellHeight,
            int cells,
            int iterations,
            int warmups,
            int elementsPerIteration,
            long totalElements,
            long totalNanos,
            long averageNanos,
            long bestNanos,
            long worstNanos,
            String message) {

        private static SlabVmCellBenchmark failed(DfcOpenClDeviceInfo device, String message) {
            return new SlabVmCellBenchmark(false, device, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, message);
        }
    }

    public record WaveSlotBufferValidation(
            int checkedElements,
            int checkedSlots,
            double maxAbsError) {
    }

    public record HybridFinalDensityValidation(
            int checkedElements,
            int stagedReads,
            double maxAbsError) {
    }

    public record FinalOutputValidation(
            int checkedElements,
            double maxAbsError) {
    }

    private interface LazySlabNode {
        void emitLazy(LazyBytecodeWriter out);
    }

    private record LazyRawNode(byte[] bytes) implements LazySlabNode {
        @Override
        public void emitLazy(LazyBytecodeWriter out) {
            out.write(this.bytes);
        }
    }

    private record LazyUnaryNode(LazySlabNode input, byte[] opBytes) implements LazySlabNode {
        @Override
        public void emitLazy(LazyBytecodeWriter out) {
            this.input.emitLazy(out);
            out.write(this.opBytes);
        }
    }

    private record LazyBinaryNode(LazySlabNode left, LazySlabNode right, int op) implements LazySlabNode {
        @Override
        public void emitLazy(LazyBytecodeWriter out) {
            this.left.emitLazy(out);
            this.right.emitLazy(out);
            out.write(this.op);
        }
    }

    private record LazyRangeNode(
            LazySlabNode input,
            LazySlabNode whenIn,
            LazySlabNode whenOut,
            int minConst,
            int maxConst) implements LazySlabNode {
        @Override
        public void emitLazy(LazyBytecodeWriter out) {
            this.input.emitLazy(out);
            out.write(OP_RANGE_CHOICE_JUMP);
            out.writeU16(this.minConst);
            out.writeU16(this.maxConst);
            int inPatch = out.position();
            out.writeI32(0);
            int outPatch = out.position();
            out.writeI32(0);

            int inStart = out.position();
            this.whenIn.emitLazy(out);
            out.write(OP_JUMP);
            int endPatch = out.position();
            out.writeI32(0);

            int outStart = out.position();
            this.whenOut.emitLazy(out);
            int end = out.position();
            out.patchI32(inPatch, inStart);
            out.patchI32(outPatch, outStart);
            out.patchI32(endPatch, end);
        }
    }

    private static final class LazyBytecodeWriter {
        private byte[] bytes;
        private int position;

        private LazyBytecodeWriter(int initialCapacity) {
            this.bytes = new byte[Math.max(32, initialCapacity)];
        }

        int position() {
            return this.position;
        }

        void write(int value) {
            ensureCapacity(this.position + 1);
            this.bytes[this.position++] = (byte) value;
        }

        void write(byte[] values) {
            if (values == null || values.length == 0) {
                return;
            }
            ensureCapacity(this.position + values.length);
            System.arraycopy(values, 0, this.bytes, this.position, values.length);
            this.position += values.length;
        }

        void writeU16(int value) {
            write(value & 0xFF);
            write((value >>> 8) & 0xFF);
        }

        void writeI32(int value) {
            write(value & 0xFF);
            write((value >>> 8) & 0xFF);
            write((value >>> 16) & 0xFF);
            write((value >>> 24) & 0xFF);
        }

        void patchI32(int offset, int value) {
            if (offset < 0 || offset + 3 >= this.position) {
                throw new IllegalArgumentException("invalid lazy bytecode patch offset " + offset);
            }
            this.bytes[offset] = (byte) value;
            this.bytes[offset + 1] = (byte) (value >>> 8);
            this.bytes[offset + 2] = (byte) (value >>> 16);
            this.bytes[offset + 3] = (byte) (value >>> 24);
        }

        byte[] toByteArray() {
            return Arrays.copyOf(this.bytes, this.position);
        }

        private void ensureCapacity(int needed) {
            if (needed <= this.bytes.length) {
                return;
            }
            int next = this.bytes.length;
            while (next < needed) {
                next = Math.max(next + 1, next * 2);
            }
            this.bytes = Arrays.copyOf(this.bytes, next);
        }
    }

    private record FinalOutputStageBuild(
            int targetSlot,
            int targetSlotCount,
            DfcOpenClGeneratedNoiseSource.BuildResult source,
            byte[] bytecode,
            double[] constants,
            int targetSlotBufferIndex) {
        static FinalOutputStageBuild generated(DfcOpenClGeneratedNoiseSource.BuildResult source) {
            return generated(-1, source);
        }

        static FinalOutputStageBuild generated(int targetSlot, DfcOpenClGeneratedNoiseSource.BuildResult source) {
            return generatedBatch(targetSlot, 1, source);
        }

        static FinalOutputStageBuild generatedBatch(int firstTargetSlot,
                                                    int targetSlotCount,
                                                    DfcOpenClGeneratedNoiseSource.BuildResult source) {
            return new FinalOutputStageBuild(firstTargetSlot, Math.max(1, targetSlotCount),
                    source, null, null, -1);
        }

        static FinalOutputStageBuild deviceVm(int targetSlot,
                                              byte[] bytecode,
                                              double[] constants,
                                              int targetSlotBufferIndex) {
            return new FinalOutputStageBuild(targetSlot, 1, null, bytecode, constants, targetSlotBufferIndex);
        }

        boolean deviceVm() {
            return this.source == null;
        }

        int sourceChars() {
            return this.source == null ? 0 : this.source.source().length();
        }
    }

    record FinalOutputTraceStageInfo(String group, String label, boolean deviceVm) {
    }

    private record FinalOutputTraceStageTime(String label, long nanos) {
    }

    record FinalOutputExternalPrefillTrace(
            long totalNanos,
            long allocateNanos,
            long setupNanos,
            long scanNanos,
            long[] slotNanos,
            int[] slotValues) {
    }

    private record FinalOutputExternalPrefillSlotTime(int slot, String label, long nanos, int values) {
    }

    private record FinalOutputSlotBufferInputs(double[] values, FinalOutputExternalPrefillTrace trace) {
    }

    public record GeneratedSourceCompileProbe(
            boolean passed,
            DfcOpenClDeviceInfo device,
            int startSlot,
            int endSlot,
            long compileNanos,
            int sourceChars,
            int totalNoiseOctaves,
            int coordScaleTemps,
            int coordScaleRefs,
            String message) {

        private static GeneratedSourceCompileProbe failed(DfcOpenClDeviceInfo device, String message) {
            return new GeneratedSourceCompileProbe(false, device, 0, 0, 0L, 0, 0, 0, 0, message);
        }
    }

    private record ChunkDescriptorInput(
            NoiseSpec[] specs,
            BlendedNoiseSpec[] blendedSpecs,
            boolean[] inactiveSlots) {
    }

    private record RuntimeChunk(int startSlot, int endSlot, int count, int octaves, int computed) {
    }

    private record RuntimeWavePlan(boolean[][] waves, boolean[] scheduledChunks, boolean[] directBlockedChunks) {
    }

    private record RuntimeHybridPlan(
            boolean available,
            String unavailableReason,
            OpenClCompiledPlan plan,
            DfcOpenClNoiseDescriptor descriptor,
            RuntimeOutputLayer[] outputLayers,
            int slotCount,
            int scheduledSlotCount,
            int[] slotBufferIndices,
            boolean[] stagedSlots,
            String[] waveSources,
            boolean[][] kernelWaves,
            int totalSourceChars,
            int maxSourceChars) {

        static RuntimeHybridPlan available(
                OpenClCompiledPlan plan,
                DfcOpenClNoiseDescriptor descriptor,
                RuntimeOutputLayer[] outputLayers,
                int slotCount,
                int scheduledSlotCount,
                int[] slotBufferIndices,
                boolean[] stagedSlots,
                String[] waveSources,
                boolean[][] kernelWaves,
                int totalSourceChars,
                int maxSourceChars) {
            return new RuntimeHybridPlan(true, null, plan, descriptor, outputLayers,
                    slotCount, scheduledSlotCount, slotBufferIndices, stagedSlots,
                    waveSources, kernelWaves, totalSourceChars, maxSourceChars);
        }

        static RuntimeHybridPlan unavailable(String reason) {
            return new RuntimeHybridPlan(false, reason, null, null, new RuntimeOutputLayer[0], 0, 0,
                    null, null, new String[0], new boolean[0][], 0, 0);
        }
    }

    private record RuntimeOutputLayer(
            OpenClCompiledPlan plan,
            DfcOpenClNoiseDescriptor descriptor,
            int embeddedExternIndex) {
    }

    private record EmbeddedRuntimePlan(
            OpenClCompiledPlan plan,
            int slotCount,
            RuntimeOutputLayer[] outputLayers) {
    }

    @FunctionalInterface
    public interface HoistEvaluator {
        double evaluate(double bx, double by, double bz);
    }

    public record OpenClCompiledPlan(
            String label,
            NoiseSpec[] specs,
            byte[] slabProgram,
            double[] slabConstants,
            String hoistExpression,
            HoistEvaluator hoistEvaluator,
            String[] slotCoordXExpressions,
            String[] slotCoordYExpressions,
            String[] slotCoordZExpressions,
            HoistEvaluator[] slotCoordXEvaluators,
            HoistEvaluator[] slotCoordYEvaluators,
            HoistEvaluator[] slotCoordZEvaluators,
            dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec[] blendedSpecs,
            boolean[] externalSlots,
            int[] markerExternIndices,
            DensityFunction[] externs,
            ComputedSlot[] computedSlots) {
    }

    public record ComputedSlot(
            byte[] slabProgram,
            double[] slabConstants,
            String hoistExpression,
            HoistEvaluator hoistEvaluator,
            String label) {
    }

    public record Status(
            boolean enabled,
            boolean probed,
            boolean available,
            List<DfcOpenClDeviceInfo> devices,
            boolean runtimeTested,
            boolean runtimePassed,
            DfcOpenClDeviceInfo selectedDevice,
            String runtimeBuildLog,
            String error) {

        private static Status disabled() {
            return new Status(false, false, false, List.of(), false, false, null, null, null);
        }

        private static Status enabledUnprobed() {
            return new Status(true, false, false, List.of(), false, false, null, null, null);
        }
    }
}
