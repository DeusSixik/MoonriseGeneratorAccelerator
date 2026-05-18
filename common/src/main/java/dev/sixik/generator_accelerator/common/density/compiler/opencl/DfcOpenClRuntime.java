package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import dev.sixik.generator_accelerator.common.density.compiler.natives.DfcNativeBridge;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fail-soft entry point for the experimental DFC OpenCL backend.
 *
 * <p>This class deliberately keeps device probing behind explicit config checks so
 * a disabled OpenCL backend never loads LWJGL OpenCL classes on the stable CPU/JNI path.
 */
public final class DfcOpenClRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(DfcOpenClRuntime.class);
    private static final double WRAP_AXIS_FAST_LIMIT = 16777216.0D;

    private static volatile Status cachedStatus = Status.disabled();
    private static volatile DfcOpenClDeviceEnumerator.Candidate selectedCandidate;
    private static DfcOpenClDeviceContext activeContext;
    private static volatile boolean slabVmDispatchBroken;

    private DfcOpenClRuntime() {
    }

    public static void init() {
        if (!DfcOpenClConfig.enabled()) {
            closeActiveContext();
            selectedCandidate = null;
            slabVmDispatchBroken = false;
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
            cachedStatus = Status.disabled();
            return cachedStatus;
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

    public static boolean slabVmDispatchAvailable() {
        Status status = cachedStatus;
        return DfcOpenClConfig.worldgenBridgeEnabled()
                && !slabVmDispatchBroken
                && status.enabled()
                && status.available()
                && selectedCandidate != null
                && DfcOpenClConfig.slabVmMinElements() <= DfcOpenClConfig.currentBridgeMaxElements();
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
