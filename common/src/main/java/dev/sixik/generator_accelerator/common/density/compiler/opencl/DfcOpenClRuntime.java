package dev.sixik.generator_accelerator.common.density.compiler.opencl;

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

    private static volatile Status cachedStatus = Status.disabled();
    private static volatile DfcOpenClDeviceEnumerator.Candidate selectedCandidate;
    private static DfcOpenClDeviceContext activeContext;

    private DfcOpenClRuntime() {
    }

    public static void init() {
        if (!DfcOpenClConfig.enabled()) {
            closeActiveContext();
            selectedCandidate = null;
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
            Status result = new Status(true, true, false, List.of(), false, false, null, null,
                    errorMessage(throwable));
            cachedStatus = result;
            LOGGER.warn("DFC OpenCL: probe failed: {}", result.error(), throwable);
            return result;
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
            DfcOpenClDeviceContext context = ensureActiveContext();
            double[] out = new double[DfcOpenClSlabVmSmoke.COUNT];
            DfcOpenClDeviceContext.SlabVmResult result =
                    context.evalSlabVm(DfcOpenClSlabVmSmoke.request(out));
            DfcOpenClSlabVmSmoke.validate(out);
            return new SlabVmSelfTest(true, context.deviceInfo(), result.elapsedNanos(), "ok");
        } catch (Throwable throwable) {
            closeActiveContext();
            return SlabVmSelfTest.failed(status.selectedDevice(), errorMessage(throwable));
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

    public record SlabVmSelfTest(
            boolean passed,
            DfcOpenClDeviceInfo device,
            long elapsedNanos,
            String message) {

        private static SlabVmSelfTest failed(DfcOpenClDeviceInfo device, String message) {
            return new SlabVmSelfTest(false, device, 0L, message);
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
