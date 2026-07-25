package dev.sixik.generator_accelerator.debug;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.aquifer.AquiferStats;
import dev.sixik.generator_accelerator.common.beardifier.BeardifierStats;
import dev.sixik.generator_accelerator.common.biome.climate.FlatClimateIndex;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCompiledClassRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.MapAllSession;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.DensityFunctionGpuKernelOpRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.DensityFunctionGpuPayloadBuilderRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadBatchExecutor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.DensityFunctionIrBuilderRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RandomStateCompileBudget;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkTimingStats;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiTabBarFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GADebugOverlay {
    private static final int DUMP_LIST_LIMIT = 8;
    private static final int DUMP_COMPILED_CLASS_LIMIT = 3;
    private static final int DUMP_VALUE_MAX_CHARS = 160;

    private enum DebugTab {
        DFC("DFC"),
        BIOME("Biome");

        private final String title;

        DebugTab(String title) {
            this.title = title;
        }
    }

    private static final DateTimeFormatter DUMP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static boolean visible;
    private static String actionStatus = "";
    private static String lastGpuProbeStatus = "not_run";
    private static String lastGpuLargeBatchProbeStatus = "not_run";
    private static String lastDfcCompileProbeStatus = "not_run";
    private static DebugTab activeTab = DebugTab.DFC;

    private GADebugOverlay() {
    }

    public static boolean isAvailable() {
        return GeneratorAccelerator.isDevMode();
    }

    public static void toggle() {
        if (!isAvailable()) {
            return;
        }
        visible = !visible;
    }

    public static boolean isVisible() {
        return isAvailable() && visible;
    }

    public static void render() {
        if (!isVisible()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ImGui.setNextWindowSize(620.0f, 720.0f, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(40.0f, 40.0f, ImGuiCond.FirstUseEver);

        ImBoolean open = new ImBoolean(visible);
        if (!ImGui.begin("Generator Accelerator Debug", open)) {
            ImGui.end();
            visible = open.get();
            return;
        }

        visible = open.get();
        drawDumpActions();
        ImGui.separator();
        if (ImGui.beginTabBar("ga-debug-tabs", ImGuiTabBarFlags.None)) {
            if (ImGui.beginTabItem(DebugTab.DFC.title)) {
                activeTab = DebugTab.DFC;
                drawDfcTab(minecraft);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem(DebugTab.BIOME.title)) {
                activeTab = DebugTab.BIOME;
                drawBiomeTab(minecraft);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
        ImGui.end();
    }

    private static void drawDumpActions() {
        if (ImGui.button("Copy Dump")) {
            String dump = buildDump(activeTab);
            ImGui.setClipboardText(dump);
            actionStatus = "Copied " + activeTab.title + " debug dump to clipboard (" + dump.length() + " chars).";
        }

        ImGui.sameLine();
        if (ImGui.button("Save Dump")) {
            try {
                Path dumpFile = writeDumpToFile(activeTab, buildDump(activeTab));
                actionStatus = "Saved debug dump to " + dumpFile;
            } catch (IOException exception) {
                actionStatus = "Failed to save dump: " + exception.getMessage();
                GeneratorAccelerator.LOGGER.warn("Failed to save GA debug dump", exception);
            }
        }

        if (activeTab == DebugTab.DFC) {
            ImGui.sameLine();
            if (ImGui.button("GPU Probe")) {
                GpuPayloadBatchExecutor.DebugProbeResult result = GpuPayloadBatchExecutor.runDebugProbe();
                lastGpuProbeStatus = formatGpuProbeResult(result);
                actionStatus = "GPU probe: " + lastGpuProbeStatus;
            }
            ImGui.sameLine();
            if (ImGui.button("Large GPU Probe")) {
                GpuPayloadBatchExecutor.LargeBatchProbeResult result = GpuPayloadBatchExecutor.runLargeBatchProbe();
                lastGpuLargeBatchProbeStatus = formatGpuLargeBatchProbeResult(result);
                actionStatus = "Large GPU probe: " + lastGpuLargeBatchProbeStatus;
            }
            ImGui.sameLine();
            if (ImGui.button("Compile FinalDensity Probe")) {
                lastDfcCompileProbeStatus = runFinalDensityCompileProbe(Minecraft.getInstance());
                actionStatus = "DFC compile probe: " + lastDfcCompileProbeStatus;
            }
        }

        if (!actionStatus.isBlank()) {
            ImGui.textWrapped(actionStatus);
        }
    }

    private static String formatGpuProbeResult(GpuPayloadBatchExecutor.DebugProbeResult result) {
        return String.format(Locale.ROOT,
                "success=%s, reason=%s, enabled=%s, preflight=%s/%s, persistentScope=%s/%s, disabled=%s, "
                        + "points=%d, maxAbsError=%.3g, firstGpu=%.17g, firstCpu=%.17g",
                result.success(),
                result.reason(),
                result.gpuEnabled(),
                result.preflightState(),
                result.preflightReason(),
                result.persistentScopeEnabled(),
                result.persistentScopeActive(),
                result.disabledReason(),
                result.points(),
                result.maxAbsError(),
                result.firstGpuValue(),
                result.firstCpuValue());
    }

    private static String formatGpuLargeBatchProbeResult(GpuPayloadBatchExecutor.LargeBatchProbeResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.ROOT,
                "success=%s, reason=%s, enabled=%s, preparedLauncher=%s, directLauncher=%s, preflight=%s/%s, persistentScope=%s/%s, disabled=%s",
                result.success(),
                result.reason(),
                result.gpuEnabled(),
                result.preparedLauncherEnabled(),
                result.directGeneratedLauncherEnabled(),
                result.preflightState(),
                result.preflightReason(),
                result.persistentScopeEnabled(),
                result.persistentScopeActive(),
                result.disabledReason()));
        for (GpuPayloadBatchExecutor.LargeBatchProbeSample sample : result.samples()) {
            builder.append(String.format(Locale.ROOT,
                    "; %dpts success=%s gpu=%.3fms warm=%s/%.3fms warmErr=%.3g warmFirst=%.17g warmReason=%s"
                            + " cpu=%.3fms err=%.3g first=%.17g/%.17g reason=%s"
                            + " direct=%s/%.3fms directErr=%.3g directFirst=%.17g directReason=%s",
                    sample.points(),
                    sample.success(),
                    sample.gpuNanos() / 1_000_000.0D,
                    sample.warmSuccess(),
                    sample.warmGpuNanos() / 1_000_000.0D,
                    sample.warmMaxAbsError(),
                    sample.warmFirstGpuValue(),
                    sample.warmReason(),
                    sample.cpuNanos() / 1_000_000.0D,
                    sample.maxAbsError(),
                    sample.firstGpuValue(),
                    sample.firstCpuValue(),
                    sample.reason(),
                    sample.directSuccess(),
                    sample.directGpuNanos() / 1_000_000.0D,
                    sample.directMaxAbsError(),
                    sample.directFirstGpuValue(),
                    sample.directReason()));
        }
        return builder.toString();
    }

    private static String runFinalDensityCompileProbe(Minecraft minecraft) {
        if (minecraft.level == null) {
            return "success=false, reason=no client level";
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return "success=false, reason=no integrated server";
        }
        ServerLevel serverLevel = server.getLevel(minecraft.level.dimension());
        if (serverLevel == null) {
            serverLevel = server.overworld();
        }
        if (serverLevel == null) {
            return "success=false, reason=no server level";
        }
        try {
            RouterPipeline.DebugCompileProbeResult result = RouterPipeline.compileDebugRoot(
                    serverLevel.getChunkSource().randomState().router(),
                    "finalDensity");
            return formatDfcCompileProbeResult(serverLevel, result);
        } catch (Throwable throwable) {
            return "success=false, reason=" + throwable;
        }
    }

    private static String formatDfcCompileProbeResult(
            ServerLevel level,
            RouterPipeline.DebugCompileProbeResult result) {
        return String.format(Locale.ROOT,
                "dimension=%s, root=%s, success=%s, reason=%s, elapsedMs=%d, uniqueNodes=%d, helpers=%d, "
                        + "noiseInline=%d/%d, gpuEligible=%s(blockers=%d, first=%s), "
                        + "gpuPayload=%s(nodes=%d, externInputs=%d, firstUnsupported=%s:%s), "
                        + "parity=%s(points=%d, maxAbsError=%.3g), "
                        + "source=%s, compiled=%s",
                level.dimension().location(),
                result.rootName(),
                result.success(),
                result.reason(),
                result.elapsedMs(),
                result.uniqueNodes(),
                result.helpersEmitted(),
                result.noisesSpecialized(),
                result.octavesUnrolled(),
                result.gpuEligible(),
                result.gpuBlockerCount(),
                result.firstGpuBlocker(),
                result.gpuPayloadSupported(),
                result.gpuPayloadNodes(),
                result.gpuPayloadExternInputs(),
                result.firstUnsupportedNode(),
                result.firstUnsupportedDetail(),
                result.parityChecked() ? (result.parityPassed() ? "passed" : "failed") : "skipped",
                result.parityPoints(),
                result.parityMaxAbsError(),
                result.sourceClass(),
                result.compiledClass());
    }

    private static void drawDfcTab(Minecraft minecraft) {
        drawEnvironment(minecraft);
        ImGui.separator();
        drawRouterPipeline();
        ImGui.separator();
        drawCellFill();
        ImGui.separator();
        drawNoiseChunkTimingStats();
        ImGui.separator();
        drawAquiferStats();
        ImGui.separator();
        drawBeardifierStats();
        ImGui.separator();
        drawSplineStats();
        ImGui.separator();
        drawRegistryWarmer();
    }

    private static void drawBiomeTab(Minecraft minecraft) {
        drawEnvironment(minecraft);
        ImGui.separator();
        drawBiomeStats();
    }

    private static void drawEnvironment(Minecraft minecraft) {
        if (!ImGui.collapsingHeader("Environment", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        ImGui.text("Dev mode: " + GeneratorAccelerator.isDevMode());
        ImGui.text("Platform: " + GeneratorAccelerator.getPlatform());
        ImGui.text("Game dir: " + GeneratorAccelerator.getGameFolder());
        ImGui.text("FPS: " + minecraft.getFps());
        ImGui.text("Level loaded: " + (minecraft.level != null));
        ImGui.text("Player loaded: " + (minecraft.player != null));
        ImGui.text("Screen: " + (minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName()));
    }

    private static void drawRouterPipeline() {
        if (!ImGui.collapsingHeader("Router Pipeline", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        RouterPipeline.Stats stats = RouterPipeline.snapshotStats();
        DensityFunctionIrBuilderRegistry.Stats irBuilderStats = DensityFunctionIrBuilderRegistry.snapshotStats();
        DensityFunctionGpuPayloadBuilderRegistry.Stats payloadBuilderStats =
                DensityFunctionGpuPayloadBuilderRegistry.snapshotStats();
        DensityFunctionGpuKernelOpRegistry.Stats kernelOpStats =
                DensityFunctionGpuKernelOpRegistry.snapshotStats();
        RandomStateCompileBudget.Stats randomStateBudgetStats = RandomStateCompileBudget.snapshotStats();
        CompiledDensityFunction.MapAllStats mapAllStats = CompiledDensityFunction.snapshotMapAllStats();
        MapAllSession.Stats sessionStats = MapAllSession.snapshotStats();
        DfcCacheFastPath.Stats cacheFastPathStats = DfcCacheFastPath.snapshotStats();

        ImGui.text("RandomState compile acquired/skipped/max: " + randomStateBudgetStats.acquired()
                + "/" + randomStateBudgetStats.skipped() + "/" + randomStateBudgetStats.max());
        ImGui.text("RandomState sampler compile: " + randomStateBudgetStats.compileSampler());
        ImGui.text("RandomState router roots: " + randomStateBudgetStats.routerRoots());
        ImGui.text("IR builders registered/matches/lowered/failures: " + irBuilderStats.registeredBuilders()
                + "/" + irBuilderStats.matches() + "/" + irBuilderStats.lowered() + "/" + irBuilderStats.failures());
        drawStringList("IR builder ids", irBuilderStats.builderIds());
        ImGui.text("GPU payload builders registered/matches/encoded/failures: "
                + payloadBuilderStats.registeredBuilders()
                + "/" + payloadBuilderStats.matches()
                + "/" + payloadBuilderStats.encoded()
                + "/" + payloadBuilderStats.failures());
        drawStringList("GPU payload builder ids", payloadBuilderStats.builderIds());
        ImGui.text("GPU kernel ops registered/lookups/misses/source fragments: "
                + kernelOpStats.registeredOps()
                + "/" + kernelOpStats.lookups()
                + "/" + kernelOpStats.misses()
                + "/" + kernelOpStats.sourceFragments());
        drawStringList("GPU kernel op ids", kernelOpStats.opIds());
        ImGui.text("Roots compiled: " + stats.rootsCompiled());
        ImGui.text("Global class cache size: " + stats.globalClassCacheSize());
        ImGui.text("Unique nodes: " + stats.uniqueNodes());
        ImGui.text("Saved by CSE: " + stats.savedByCse());
        ImGui.text("Helpers emitted: " + stats.helpersEmitted());
        ImGui.text("Optimizer rewrites: " + stats.optimizerRewrites());
        ImGui.text("Noise inlined: " + stats.noisesInlined() + " / octaves=" + stats.octavesInlined());
        ImGui.text("Blended inlined: " + stats.blendedInlined() + " / octaves=" + stats.blendedOctavesEmitted());
        ImGui.text("Global class cache hits: " + stats.globalClassCacheHits());
        ImGui.text("Global codegen misses: " + stats.globalCodegenCacheMisses());
        ImGui.text("Noise mixin failures: " + stats.noiseMixinFailures());
        ImGui.text("Blended mixin failures: " + stats.blendedMixinFailures());
        ImGui.text("Octaves skipped: " + stats.octavesSkipped());
        ImGui.text("Cache bytes saved: " + stats.globalClassCacheBytesSaved());
        ImGui.text("Cache shared instances: " + stats.globalClassCacheInstancesShared());
        ImGui.text("Shape hits across exact misses: " + stats.globalClassCacheShapeHitsAcrossExactMisses());
        ImGui.text("Lattice plans emitted: " + stats.latticePlansEmitted());
        ImGui.text("Lattice fallbacks: " + stats.latticeFallbacks());
        ImGui.text("Cell ADD lattice/beardifier/extern specializations: "
                + stats.cellAddLatticeSpecializedRoots() + "/"
                + stats.cellAddBeardifierSpecializedRoots() + "/"
                + stats.cellAddExternSpecializedRoots());
        ImGui.text("Cell scalar marker specializations: " + stats.cellScalarMarkerSpecializedRoots());
        ImGui.text("GPU eligible / blocked roots: " + stats.gpuEligibleRoots() + "/" + stats.gpuBlockedRoots());
        ImGui.text("GPU blockers total: " + stats.gpuBlockersTotal());
        drawStringList("GPU blocker counts", stats.gpuBlockerCounts());
        ImGui.text("GPU payload ready / blocked roots: " + stats.gpuPayloadReadyRoots()
                + "/" + stats.gpuPayloadBlockedRoots());
        ImGui.text("GPU payload nodes total: " + stats.gpuPayloadNodesTotal());
        drawStringList("GPU payload unsupported", stats.gpuPayloadUnsupportedCounts());
        ImGui.text("GPU payload parity checks/pass/fail: " + stats.gpuPayloadParityChecks()
                + "/" + stats.gpuPayloadParityPasses() + "/" + stats.gpuPayloadParityFailures());
        ImGui.text("GPU payload parity points/max error: " + stats.gpuPayloadParityPoints()
                + "/" + stats.gpuPayloadParityMaxAbsError());
        if (!"none".equals(stats.gpuPayloadParityFirstFailure())) {
            ImGui.textWrapped("GPU payload first mismatch: " + stats.gpuPayloadParityFirstFailure());
        }
        ImGui.text("GPU runtime enabled/preflight: "
                + Boolean.getBoolean(GpuPayloadBatchExecutor.GPU_ENABLED_PROPERTY)
                + "/" + GpuPayloadBatchExecutor.preflightStateName());
        ImGui.text("GPU runtime persistent scope enabled/active: "
                + GpuPayloadBatchExecutor.persistentRuntimeScopeEnabled()
                + "/" + GpuPayloadBatchExecutor.persistentRuntimeScopeActive());
        ImGui.textWrapped("GPU runtime reason: " + GpuPayloadBatchExecutor.disabledReason()
                + " / preflight=" + GpuPayloadBatchExecutor.preflightReason());
        ImGui.text("GPU runtime parity remaining/epsilon: "
                + GpuPayloadBatchExecutor.runtimeParityRemaining()
                + "/" + GpuPayloadBatchExecutor.runtimeParityEpsilon());
        ImGui.text("GPU runtime batch remaining/max: "
                + GpuPayloadBatchExecutor.runtimeBatchRemaining()
                + "/" + GpuPayloadBatchExecutor.runtimeBatchBudgetMax());
        ImGui.text("GPU runtime min points: " + GpuPayloadBatchExecutor.runtimeMinPoints());
        ImGui.text("GPU runtime lock wait nanos: " + GpuPayloadBatchExecutor.runtimeLockWaitNanos());
        if (!stats.gpuPayloadBatchRuntimeGateCounts().isEmpty()) {
            ImGui.textWrapped("GPU runtime gates: " + stats.gpuPayloadBatchRuntimeGateCounts());
        }
        ImGui.text("GPU batch attempts/gpu/fallback: " + stats.gpuPayloadBatchAttempts()
                + "/" + stats.gpuPayloadBatchGpuSuccesses() + "/" + stats.gpuPayloadBatchCpuFallbacks());
        ImGui.text("GPU batch points: " + stats.gpuPayloadBatchPoints());
        ImGui.text("GPU batch points gpu/fallback: " + stats.gpuPayloadBatchGpuSuccessPoints()
                + "/" + stats.gpuPayloadBatchCpuFallbackPoints());
        ImGui.text("GPU batch nanos extern/invoke/parity/total: "
                + stats.gpuPayloadBatchExternNanos()
                + "/" + stats.gpuPayloadBatchInvokeNanos()
                + "/" + stats.gpuPayloadBatchParityNanos()
                + "/" + stats.gpuPayloadBatchTotalNanos());
        ImGui.text("GPU batch cold invokes/nanos: " + stats.gpuPayloadBatchColdInvokes()
                + "/" + stats.gpuPayloadBatchColdInvokeNanos());
        ImGui.text("GPU batch warm invokes/nanos: " + stats.gpuPayloadBatchWarmInvokes()
                + "/" + stats.gpuPayloadBatchWarmInvokeNanos());
        ImGui.text("GPU runtime lock wait/held/entries/busy: "
                + stats.gpuPayloadBatchRuntimeLockWaitNanos()
                + "/" + stats.gpuPayloadBatchRuntimeLockHeldNanos()
                + "/" + stats.gpuPayloadBatchRuntimeLockEntries()
                + "/" + stats.gpuPayloadBatchRuntimeLockBusySkips());
        ImGui.text("GPU microbatch launches/requests/slots/singles: "
                + stats.gpuPayloadBatchMicroLaunches()
                + "/" + stats.gpuPayloadBatchMicroRequests()
                + "/" + stats.gpuPayloadBatchMicroSlots()
                + "/" + stats.gpuPayloadBatchMicroSingles());
        ImGui.text("GPU microbatch skipped launches/requests: "
                + stats.gpuPayloadBatchMicroSkippedLaunches()
                + "/" + stats.gpuPayloadBatchMicroSkippedRequests());
        ImGui.text("GPU runtime backoff triggers/skips/windows: "
                + stats.gpuPayloadBatchRuntimeBackoffTriggers()
                + "/" + stats.gpuPayloadBatchRuntimeBackoffSkips()
                + "/" + stats.gpuPayloadBatchRuntimeBackoffBatches());
        ImGui.text("GPU prepared launcher cache hits/misses: "
                + stats.gpuPayloadBatchPreparedCacheHits()
                + "/" + stats.gpuPayloadBatchPreparedCacheMisses());
        ImGui.textWrapped("GPU batch static args: " + stats.gpuPayloadBatchStaticArgs());
        ImGui.textWrapped("GPU batch dynamic args: " + stats.gpuPayloadBatchDynamicArgs());
        ImGui.text("GPU prepared stages upload/bind/submit/wait/finish/readback: "
                + stats.gpuPayloadBatchPreparedUploadNanos()
                + "/" + stats.gpuPayloadBatchPreparedBindNanos()
                + "/" + stats.gpuPayloadBatchPreparedEnqueueSubmitNanos()
                + "/" + stats.gpuPayloadBatchPreparedEnqueueWaitNanos()
                + "/" + stats.gpuPayloadBatchPreparedQueueFinishNanos()
                + "/" + stats.gpuPayloadBatchPreparedReadbackNanos());
        ImGui.text("GPU prepared counts alloc/reuse/upload/bind/readback: "
                + stats.gpuPayloadBatchPreparedBufferAllocateCount()
                + "/" + stats.gpuPayloadBatchPreparedBufferReuseCount()
                + "/" + stats.gpuPayloadBatchPreparedUploadCount()
                + "/" + stats.gpuPayloadBatchPreparedBindCount()
                + "/" + stats.gpuPayloadBatchPreparedReadbackCount());
        if (!"none".equals(stats.gpuPayloadBatchFirstFallback())) {
            ImGui.textWrapped("GPU batch first fallback: " + stats.gpuPayloadBatchFirstFallback());
        }
        ImGui.text("GPU batch runtime parity checks/pass/fail: "
                + stats.gpuPayloadBatchRuntimeParityChecks()
                + "/" + stats.gpuPayloadBatchRuntimeParityPasses()
                + "/" + stats.gpuPayloadBatchRuntimeParityFailures());
        ImGui.text("GPU batch runtime parity points/max error: "
                + stats.gpuPayloadBatchRuntimeParityPoints()
                + "/" + stats.gpuPayloadBatchRuntimeParityMaxAbsError());
        if (!"none".equals(stats.gpuPayloadBatchRuntimeParityFirstFailure())) {
            ImGui.textWrapped("GPU batch runtime parity first mismatch: "
                    + stats.gpuPayloadBatchRuntimeParityFirstFailure());
        }
        ImGui.text("Lazy wrappers: " + stats.lazyWrappersCreated());
        ImGui.text("Lazy resolve attempts: " + stats.lazyResolveAttempts());
        ImGui.text("Lazy successful compiles: " + stats.lazySuccessfulCompiles());
        ImGui.text("Lazy compile failures: " + stats.lazyCompileFailures());
        ImGui.text("Lazy compile fallbacks: " + stats.lazyCompileFallbacks());
        ImGui.text("MapAll identity no-ops: " + mapAllStats.identityNoOps());
        ImGui.text("MapAll rebinds: " + mapAllStats.rebinds());
        ImGui.text("MapAll sessions: " + sessionStats.sessionsCreated());
        ImGui.text("MapAll memo hits/misses: " + sessionStats.memoHits() + "/" + sessionStats.memoMisses());
        ImGui.text("MapAll max memo size: " + sessionStats.maxMemoSize());
        ImGui.text("Cache fast path eligible: " + cacheFastPathStats.eligibleCalls());
        ImGui.text("Cache fast path hits/misses: " + cacheFastPathStats.hits() + "/" + cacheFastPathStats.misses());
        ImGui.text("Cache fast path disabled/non-access: "
                + cacheFastPathStats.disabledFallbacks() + "/" + cacheFastPathStats.nonAccessFallbacks());
    }

    private static void drawCellFill() {
        if (!ImGui.collapsingHeader("Cell Fill", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        DfcCellFillStats.Stats cellFillStats = DfcCellFillStats.snapshot();
        DfcCellFillParity.Stats parityStats = DfcCellFillParity.snapshotStats();

        ImGui.text("Cell fill stats enabled: " + cellFillStats.enabled());
        ImGui.text("Compiled/scalar/unknown: " + cellFillStats.cellCompiled() + "/"
                + cellFillStats.cellScalar() + "/" + cellFillStats.cellUnknown());
        ImGui.text("XZ slab columns: " + cellFillStats.cellXzSlab());
        ImGui.text("Columns scalar/java: " + cellFillStats.columnsScalar() + "/"
                + cellFillStats.columnsJavaBatched());
        ImGui.text("Extern accumulate/scalar residual: " + cellFillStats.cellExternAccumulate()
                + "/" + cellFillStats.cellExternScalarResidual());
        ImGui.text("Cell GPU payload ready/blocked: " + cellFillStats.cellGpuPayloadReady()
                + "/" + cellFillStats.cellGpuPayloadBlocked());

        drawStringList("Fast filler classes", cellFillStats.fastFillerClasses().stream()
                .map(stat -> stat.className() + " = " + stat.calls())
                .toList());
        drawStringList("Source filler classes", cellFillStats.sourceFillerClasses());
        drawStringList("Residual extern fallback classes", cellFillStats.residualExternFallbackClasses());
        drawStringList("Cell GPU first blockers", cellFillStats.cellGpuFirstBlockers());
        drawStringList("Cell GPU unsupported nodes", cellFillStats.cellGpuUnsupportedNodes());

        ImGui.separator();
        ImGui.text("Parity enabled: " + parityStats.enabled());
        ImGui.text("Parity checks/pass/fail/skip: " + parityStats.checks() + "/"
                + parityStats.passes() + "/" + parityStats.failures() + "/" + parityStats.skipped());
        ImGui.text("Parity candidates/fast/lazy/fallbacks: " + parityStats.candidates() + "/"
                + parityStats.fastEligible() + "/" + parityStats.lazyFastEligible() + "/" + parityStats.fallbacks());
        ImGui.text("Parity remaining: " + parityStats.remaining() + " / " + parityStats.maxChecks());
        ImGui.text("Parity epsilon: " + parityStats.epsilon());
        drawStringList("Parity fallback classes", parityStats.fallbackClasses());
    }

    private static void drawNoiseChunkTimingStats() {
        if (!ImGui.collapsingHeader("NoiseChunk Timing", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        NoiseChunkTimingStats.Stats stats = NoiseChunkTimingStats.snapshotStats();
        ImGui.text("Enabled: " + stats.enabled());
        ImGui.text("FillSlice calls/ms/avg ns: " + stats.fillSliceCalls()
                + "/" + formatMillis(stats.fillSliceTotalNanos())
                + "/" + formatAverageNanos(stats.fillSliceTotalNanos(), stats.fillSliceCalls()));
        ImGui.text("FillSlice batch surface points total/avg/max: "
                + stats.fillSliceBatchSurfacePoints()
                + "/" + formatAverage(stats.fillSliceBatchSurfacePoints(), stats.fillSliceCalls())
                + "/" + stats.fillSliceBatchSurfaceMaxPoints());
        ImGui.text("FillSlice batch surface avg columns/y/interpolators: "
                + formatAverage(stats.fillSliceBatchSurfaceColumns(), stats.fillSliceCalls())
                + "/" + formatAverage(stats.fillSliceBatchSurfaceY(), stats.fillSliceCalls())
                + "/" + formatAverage(stats.fillSliceBatchSurfaceInterpolators(), stats.fillSliceCalls()));
        ImGui.text("FillSlice payload roots ready/total/extern: "
                + stats.fillSlicePayloadReadyRoots()
                + "/" + stats.fillSlicePayloadRoots()
                + "/" + stats.fillSlicePayloadExternRoots());
        ImGui.text("FillSlice payload points ready/total/extern: "
                + stats.fillSlicePayloadReadyPoints()
                + "/" + stats.fillSlicePayloadPoints()
                + "/" + stats.fillSlicePayloadExternPoints());
        ImGui.text("FillSlice lazy compiles attempt/success/fail/budget: "
                + stats.fillSliceLazyCompileAttempts()
                + "/" + stats.fillSliceLazyCompileSuccesses()
                + "/" + stats.fillSliceLazyCompileFailures()
                + "/" + stats.fillSliceLazyCompileBudgetSkips());
        ImGui.text("SelectCellYZ calls/ms/avg ns: " + stats.selectCellYzCalls()
                + "/" + formatMillis(stats.selectCellYzTotalNanos())
                + "/" + formatAverageNanos(stats.selectCellYzTotalNanos(), stats.selectCellYzCalls()));
        ImGui.text("SelectCellYZ setup/cache ms: "
                + formatMillis(stats.selectCellYzSetupNanos())
                + "/" + formatMillis(stats.selectCellYzCacheFillNanos()));
        ImGui.text("SelectCellYZ setup/cache avg ns: "
                + formatAverageNanos(stats.selectCellYzSetupNanos(), stats.selectCellYzCalls())
                + "/" + formatAverageNanos(stats.selectCellYzCacheFillNanos(), stats.selectCellYzCalls()));
        ImGui.text("SelectCellYZ fast fill calls/ms/avg ns: " + stats.selectCellYzFastFillCalls()
                + "/" + formatMillis(stats.selectCellYzFastFillNanos())
                + "/" + formatAverageNanos(stats.selectCellYzFastFillNanos(), stats.selectCellYzFastFillCalls()));
        ImGui.text("SelectCellYZ fallback fill calls/ms/avg ns: " + stats.selectCellYzFallbackFillCalls()
                + "/" + formatMillis(stats.selectCellYzFallbackFillNanos())
                + "/" + formatAverageNanos(stats.selectCellYzFallbackFillNanos(), stats.selectCellYzFallbackFillCalls()));
        ImGui.text("SelectCellYZ lazy resolve calls/ms/avg ns: " + stats.selectCellYzLazyResolveCalls()
                + "/" + formatMillis(stats.selectCellYzLazyResolveNanos())
                + "/" + formatAverageNanos(stats.selectCellYzLazyResolveNanos(), stats.selectCellYzLazyResolveCalls()));
        ImGui.text("SelectCellYZ Ap2 primary calls/ms/avg ns: " + stats.selectCellYzAp2PrimaryCalls()
                + "/" + formatMillis(stats.selectCellYzAp2PrimaryNanos())
                + "/" + formatAverageNanos(stats.selectCellYzAp2PrimaryNanos(), stats.selectCellYzAp2PrimaryCalls()));
        ImGui.text("SelectCellYZ Ap2 secondary calls/ms/avg ns: " + stats.selectCellYzAp2SecondaryCalls()
                + "/" + formatMillis(stats.selectCellYzAp2SecondaryNanos())
                + "/" + formatAverageNanos(stats.selectCellYzAp2SecondaryNanos(), stats.selectCellYzAp2SecondaryCalls()));
        ImGui.text("SelectCellYZ Ap2 zero secondary skips: " + stats.selectCellYzAp2ZeroSecondarySkips());
        drawStringList("SelectCellYZ fast filler classes", stats.selectCellYzFastFillerClasses());
        drawStringList("SelectCellYZ fast filler details", stats.selectCellYzFastFillerDetails());
        drawStringList("SelectCellYZ fallback filler classes", stats.selectCellYzFallbackFillerClasses());
        drawStringList("SelectCellYZ fallback filler details", stats.selectCellYzFallbackFillerDetails());
        drawStringList("FillSlice payload missing classes", stats.fillSlicePayloadMissingClasses());
        drawStringList("FillSlice payload blocked reasons", stats.fillSlicePayloadBlockedReasons());
    }

    private static void drawSplineStats() {
        if (!ImGui.collapsingHeader("Spline Runtime", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        DfcSplineStats.Stats splineStats = DfcSplineStats.snapshot();
        List<DfcSplineStats.ClassStats> topClasses = DfcSplineStats.snapshotTopClasses(5);

        ImGui.text("Enabled: " + splineStats.enabled());
        ImGui.text("Search mode: " + Codegen.splineSearchModeName()
                + " / linearMaxPoints=" + Codegen.SPLINE_LINEAR_SEARCH_MAX_POINTS);
        ImGui.text("Segment LUT: " + Codegen.SPLINE_SEGMENT_LUT_ENABLED
                + " / minPoints=" + Codegen.SPLINE_SEGMENT_LUT_MIN_POINTS
                + " / buckets=" + Codegen.SPLINE_SEGMENT_LUT_BUCKETS);
        ImGui.text("Calls linear/binary/lut: " + splineStats.calls() + " / "
                + splineStats.linearCalls() + " / " + splineStats.binaryCalls() + " / " + splineStats.lutCalls());
        ImGui.text("Exit interior/left/right: " + splineStats.interiorCalls() + " / "
                + splineStats.leftExtrapolationCalls() + " / " + splineStats.rightExtrapolationCalls());
        ImGui.text("Total ms: " + formatMillis(splineStats.totalNanos()));
        ImGui.text("Linear ms: " + formatMillis(splineStats.linearNanos()));
        ImGui.text("Binary ms: " + formatMillis(splineStats.binaryNanos()));
        ImGui.text("LUT ms: " + formatMillis(splineStats.lutNanos()));
        ImGui.text("Buckets <=2 / 3..4 / 5..8 / >=9: "
                + formatBucket(splineStats.bucketLe2()) + " | "
                + formatBucket(splineStats.bucket3To4()) + " | "
                + formatBucket(splineStats.bucket5To8()) + " | "
                + formatBucket(splineStats.bucketGe9()));

        if (ImGui.treeNode("Top spline classes")) {
            if (topClasses.isEmpty()) {
                ImGui.textDisabled("No samples yet");
            } else {
                for (DfcSplineStats.ClassStats stat : topClasses) {
                    ImGui.text(stat.className());
                    ImGui.textDisabled("source=" + stat.sourceRootClass() + ", root=" + stat.rootDebug());
                    ImGui.textDisabled("spline=" + stat.splineDebug());
                    ImGui.bulletText("calls=" + stat.calls()
                            + ", totalMs=" + formatMillis(stat.totalNanos())
                            + ", avgNs=" + formatAverageNanos(stat.totalNanos(), stat.calls())
                            + ", linear=" + stat.linearCalls()
                            + ", binary=" + stat.binaryCalls()
                            + ", lut=" + stat.lutCalls());
                    ImGui.bulletText("interior=" + stat.interiorCalls()
                            + ", left=" + stat.leftExtrapolationCalls()
                            + ", right=" + stat.rightExtrapolationCalls());
                    ImGui.bulletText("point3=" + formatBucket(stat.point3())
                            + ", point4=" + formatBucket(stat.point4()));
                    ImGui.bulletText("<=2=" + formatBucket(stat.bucketLe2())
                            + ", 3..4=" + formatBucket(stat.bucket3To4())
                            + ", 5..8=" + formatBucket(stat.bucket5To8())
                            + ", >=9=" + formatBucket(stat.bucketGe9()));
                }
            }
            ImGui.treePop();
        }
    }

    private static void drawAquiferStats() {
        if (!ImGui.collapsingHeader("Aquifer", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        AquiferStats.Stats stats = AquiferStats.snapshotStats();
        ImGui.text("computeSubstance calls: " + stats.computeSubstanceCalls());
        ImGui.text("Positive density returns: " + stats.positiveDensityReturns());
        ImGui.text("Global lava returns: " + stats.globalLavaReturns());
        ImGui.text("Refresh dist calls: " + stats.refreshDistCalls());
        ImGui.text("Barrier noise computes: " + stats.barrierNoiseComputes());
        ImGui.text("Water-below-lava returns: " + stats.waterBelowLavaReturns());
        ImGui.text("Pressure abort returns: " + stats.pressureAbortReturns());
        ImGui.text("Final solid returns: " + stats.finalSolidReturns());
        ImGui.text("Lazy third resolves: " + stats.lazyThirdResolves());
        ImGui.text("Refresh dist timed calls/ns: " + stats.refreshDistTimedCalls() + "/" + stats.refreshDistTotalNanos());
        ImGui.text("Lazy third timed calls/ns: " + stats.lazyThirdTimedCalls() + "/" + stats.lazyThirdTotalNanos());
        ImGui.text("Aquifer status timed calls/ns: " + stats.aquiferStatusTimedCalls() + "/" + stats.aquiferStatusTotalNanos());
    }

    private static void drawBeardifierStats() {
        if (!ImGui.collapsingHeader("Beardifier", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        BeardifierStats.Stats stats = BeardifierStats.snapshotStats();
        ImGui.text("Enabled: " + stats.enabled());
        ImGui.text("Compute/fill/accumulate cells: " + stats.computeCellCalls() + "/"
                + stats.fillCellCalls() + "/" + stats.accumulateCellCalls());
        ImGui.text("Compute single/bulk-logical cells: " + stats.computeCellSingleCalls() + "/"
                + stats.computeCellBulkLogicalCalls());
        ImGui.text("Cell active pieces/junctions: " + stats.cellActivePieces() + "/" + stats.cellActiveJunctions());
        ImGui.text("Outside influence/cache hits/empty active: " + stats.outsideInfluenceReturns() + "/"
                + stats.outsideCellCacheHits() + "/" + stats.emptyActiveReturns());
        ImGui.text("Columns processed: " + stats.columnsProcessed());
        ImGui.text("Column cache hits: " + stats.columnCacheHits());
        ImGui.text("Direct compute fallbacks: " + stats.directComputeFallbacks());
        ImGui.text("Empty columns after filter: " + stats.emptyColumnsAfterFilter());
        ImGui.text("Column pieces before/after filter: " + stats.columnPiecesBeforeFilter() + "/"
                + stats.columnPiecesAfterFilter());
        ImGui.text("Column junctions before/after filter: " + stats.columnJunctionsBeforeFilter() + "/"
                + stats.columnJunctionsAfterFilter());
        ImGui.text("Filtered bury/thin/box/encapsulate: " + stats.filteredBuryPieces() + "/"
                + stats.filteredThinPieces() + "/" + stats.filteredBoxPieces() + "/"
                + stats.filteredEncapsulatePieces());
        ImGui.text("Compute cell timed calls/ns: " + stats.computeCellTimedCalls() + "/"
                + stats.computeCellTotalNanos());
        ImGui.text("Fill cell timed calls/ns: " + stats.fillCellTimedCalls() + "/"
                + stats.fillCellTotalNanos());
        ImGui.text("Accumulate cell timed calls/ns: " + stats.accumulateCellTimedCalls() + "/"
                + stats.accumulateCellTotalNanos());
        ImGui.text("Rebuild column timed calls/ns: " + stats.rebuildColumnTimedCalls() + "/"
                + stats.rebuildColumnTotalNanos());
        ImGui.text("Direct compute timed calls/ns: " + stats.directComputeTimedCalls() + "/"
                + stats.directComputeTotalNanos());
    }

    private static void drawRegistryWarmer() {
        if (!ImGui.collapsingHeader("Registry Warmer", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        RegistryWarmer.Stats stats = RegistryWarmer.snapshotStats();
        ImGui.text("Calls: " + stats.calls());
        ImGui.text("Skipped duplicate calls: " + stats.skippedDuplicateCalls());
        ImGui.text("Skipped duplicate entries: " + stats.skippedDuplicateEntries());
        ImGui.text("Warmed routers: " + stats.warmedRouters());
        ImGui.text("Warmed density functions: " + stats.warmedDensityFunctions());
        ImGui.text("Failed entries: " + stats.failedEntries());
        ImGui.text("Budget skips: " + stats.budgetSkips());
    }

    private static void drawBiomeStats() {
        if (!ImGui.collapsingHeader("Biome Climate", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        FlatClimateIndex.Stats stats = FlatClimateIndex.snapshotStats();
        ImGui.text("Index builds: " + stats.indexBuilds());
        ImGui.text("Last nodes/leaves/values: " + stats.lastNodeCount() + "/"
                + stats.lastLeafCount() + "/" + stats.lastValueCount());
        ImGui.text("Bounds bytes: " + stats.lastBoundsBytes());
        ImGui.text("Active dimension mask: 0x" + Integer.toHexString(stats.activeDimensionMask()));
        ImGui.text("Full query dimensions: " + stats.fullQueryDimensions());
        ImGui.text("Has offset distances: " + stats.hasOffsetDistances());
        ImGui.text("Linear search index: " + stats.linearSearchIndex()
                + " / threshold=" + stats.linearSearchThreshold());
        ImGui.text("Query cache size: " + stats.queryCacheSize()
                + " / adaptive=" + stats.adaptiveQueryCache());
        ImGui.text("Query cache disable probes: " + stats.queryCacheDisableProbes()
                + " / hit-rate shift=" + stats.queryCacheDisableHitRateShift());
        ImGui.text("No-offset cap order: " + stats.noOffsetCapOrder());
        ImGui.separator();
        ImGui.text("Searches: " + stats.searches());
        ImGui.text("Last-value cache hits: " + stats.lastValueCacheHits());
        ImGui.text("Query cache probes/hits/disables: " + stats.queryCacheProbes()
                + "/" + stats.queryCacheHits() + "/" + stats.queryCacheDisables());
        ImGui.text("Linear/tree searches: " + stats.linearSearchCalls() + "/" + stats.treeSearchCalls());
        ImGui.text("Warm start zero hits: " + stats.warmStartZeroHits());
        ImGui.text("Second warm start zero hits: " + stats.secondWarmStartZeroHits());
        ImGui.text("Tree node visits: " + stats.treeNodeVisits());
        ImGui.text("Tree child tests/accepts: " + stats.treeChildDistanceTests() + "/" + stats.treeChildAccepts());
        ImGui.text("Tree valid children 0/1/2/3+: " + stats.treeValidChildren0() + "/"
                + stats.treeValidChildren1() + "/" + stats.treeValidChildren2() + "/" + stats.treeValidChildren3Plus());
        ImGui.text("Tree valid children 3/4/5/6: " + stats.treeValidChildren3() + "/"
                + stats.treeValidChildren4() + "/" + stats.treeValidChildren5() + "/" + stats.treeValidChildren6());
        ImGui.text("No-offset cap exits T/H/C/E/D/W/full: " + stats.noOffsetCapExitT() + "/"
                + stats.noOffsetCapExitH() + "/" + stats.noOffsetCapExitC() + "/"
                + stats.noOffsetCapExitE() + "/" + stats.noOffsetCapExitD() + "/"
                + stats.noOffsetCapExitW() + "/" + stats.noOffsetCapNoEarlyExit());
        ImGui.text("Linear leaf tests: " + stats.linearLeafTests());
    }

    private static void drawStringList(String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (ImGui.treeNode(label + "##" + label.hashCode())) {
            for (String value : values) {
                ImGui.bulletText(value);
            }
            ImGui.treePop();
        }
    }

    private static String formatBucket(DfcSplineStats.BucketStats bucket) {
        return bucket.calls() + " / " + formatMillis(bucket.nanos()) + "ms";
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0d);
    }

    private static String formatAverageNanos(long nanos, long calls) {
        if (calls <= 0L) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.1f", nanos / (double) calls);
    }

    private static String formatAverage(long total, long calls) {
        if (calls <= 0L) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.1f", total / (double) calls);
    }

    private static String buildDump(DebugTab tab) {
        return tab == DebugTab.BIOME ? buildBiomeDump() : buildDfcDump();
    }

    private static String buildDfcDump() {
        StringBuilder dump = new StringBuilder(4096);
        Minecraft minecraft = Minecraft.getInstance();
        RouterPipeline.Stats routerStats = RouterPipeline.snapshotStats();
        DensityFunctionIrBuilderRegistry.Stats irBuilderStats = DensityFunctionIrBuilderRegistry.snapshotStats();
        DensityFunctionGpuPayloadBuilderRegistry.Stats payloadBuilderStats =
                DensityFunctionGpuPayloadBuilderRegistry.snapshotStats();
        DensityFunctionGpuKernelOpRegistry.Stats kernelOpStats =
                DensityFunctionGpuKernelOpRegistry.snapshotStats();
        RandomStateCompileBudget.Stats randomStateBudgetStats = RandomStateCompileBudget.snapshotStats();
        CompiledDensityFunction.MapAllStats mapAllStats = CompiledDensityFunction.snapshotMapAllStats();
        MapAllSession.Stats sessionStats = MapAllSession.snapshotStats();
        DfcCacheFastPath.Stats cacheFastPathStats = DfcCacheFastPath.snapshotStats();
        DfcCellFillStats.Stats cellFillStats = DfcCellFillStats.snapshot();
        DfcCellFillParity.Stats parityStats = DfcCellFillParity.snapshotStats();
        NoiseChunkTimingStats.Stats noiseChunkTimingStats = NoiseChunkTimingStats.snapshotStats();
        DfcSplineStats.Stats splineStats = DfcSplineStats.snapshot();
        AquiferStats.Stats aquiferStats = AquiferStats.snapshotStats();
        BeardifierStats.Stats beardifierStats = BeardifierStats.snapshotStats();
        RegistryWarmer.Stats registryWarmerStats = RegistryWarmer.snapshotStats();
        List<DfcSplineStats.ClassStats> topSplineClasses = DfcSplineStats.snapshotTopClasses(5);
        List<DfcCompiledClassRegistry.Entry> compiledEntries = DfcCompiledClassRegistry.snapshotRecent();

        appendSection(dump, "Environment");
        appendLine(dump, "devMode", GeneratorAccelerator.isDevMode());
        appendLine(dump, "platform", GeneratorAccelerator.getPlatform());
        appendLine(dump, "gameDir", GeneratorAccelerator.getGameFolder());
        appendLine(dump, "fps", minecraft.getFps());
        appendLine(dump, "levelLoaded", minecraft.level != null);
        appendLine(dump, "playerLoaded", minecraft.player != null);
        appendLine(dump, "screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());

        appendSection(dump, "Router Pipeline");
        appendLine(dump, "randomStateCompileAcquired", randomStateBudgetStats.acquired());
        appendLine(dump, "randomStateCompileSkipped", randomStateBudgetStats.skipped());
        appendLine(dump, "randomStateCompileMax", randomStateBudgetStats.max());
        appendLine(dump, "randomStateCompileSampler", randomStateBudgetStats.compileSampler());
        appendLine(dump, "randomStateCompileRouterRoots", randomStateBudgetStats.routerRoots());
        appendLine(dump, "irBuildersRegistered", irBuilderStats.registeredBuilders());
        appendLine(dump, "irBuilderMatches", irBuilderStats.matches());
        appendLine(dump, "irBuilderLowered", irBuilderStats.lowered());
        appendLine(dump, "irBuilderFailures", irBuilderStats.failures());
        appendList(dump, "irBuilderIds", irBuilderStats.builderIds());
        appendLine(dump, "gpuPayloadBuildersRegistered", payloadBuilderStats.registeredBuilders());
        appendLine(dump, "gpuPayloadBuilderMatches", payloadBuilderStats.matches());
        appendLine(dump, "gpuPayloadBuilderEncoded", payloadBuilderStats.encoded());
        appendLine(dump, "gpuPayloadBuilderFailures", payloadBuilderStats.failures());
        appendList(dump, "gpuPayloadBuilderIds", payloadBuilderStats.builderIds());
        appendLine(dump, "gpuKernelOpsRegistered", kernelOpStats.registeredOps());
        appendLine(dump, "gpuKernelOpLookups", kernelOpStats.lookups());
        appendLine(dump, "gpuKernelOpMisses", kernelOpStats.misses());
        appendLine(dump, "gpuKernelOpSourceFragments", kernelOpStats.sourceFragments());
        appendList(dump, "gpuKernelOpIds", kernelOpStats.opIds());
        appendLine(dump, "rootsCompiled", routerStats.rootsCompiled());
        appendLine(dump, "globalClassCacheSize", routerStats.globalClassCacheSize());
        appendLine(dump, "uniqueNodes", routerStats.uniqueNodes());
        appendLine(dump, "savedByCse", routerStats.savedByCse());
        appendLine(dump, "helpersEmitted", routerStats.helpersEmitted());
        appendLine(dump, "optimizerRewrites", routerStats.optimizerRewrites());
        appendLine(dump, "noisesInlined", routerStats.noisesInlined());
        appendLine(dump, "octavesInlined", routerStats.octavesInlined());
        appendLine(dump, "blendedInlined", routerStats.blendedInlined());
        appendLine(dump, "blendedOctavesEmitted", routerStats.blendedOctavesEmitted());
        appendLine(dump, "globalClassCacheHits", routerStats.globalClassCacheHits());
        appendLine(dump, "globalCodegenCacheMisses", routerStats.globalCodegenCacheMisses());
        appendLine(dump, "noiseMixinFailures", routerStats.noiseMixinFailures());
        appendLine(dump, "blendedMixinFailures", routerStats.blendedMixinFailures());
        appendLine(dump, "octavesSkipped", routerStats.octavesSkipped());
        appendLine(dump, "globalClassCacheBytesSaved", routerStats.globalClassCacheBytesSaved());
        appendLine(dump, "globalClassCacheInstancesShared", routerStats.globalClassCacheInstancesShared());
        appendLine(dump, "globalClassCacheShapeHitsAcrossExactMisses", routerStats.globalClassCacheShapeHitsAcrossExactMisses());
        appendLine(dump, "latticePlansEmitted", routerStats.latticePlansEmitted());
        appendLine(dump, "latticeFallbacks", routerStats.latticeFallbacks());
        appendLine(dump, "cellAddLatticeSpecializedRoots", routerStats.cellAddLatticeSpecializedRoots());
        appendLine(dump, "cellAddBeardifierSpecializedRoots", routerStats.cellAddBeardifierSpecializedRoots());
        appendLine(dump, "cellAddExternSpecializedRoots", routerStats.cellAddExternSpecializedRoots());
        appendLine(dump, "cellScalarMarkerSpecializedRoots", routerStats.cellScalarMarkerSpecializedRoots());
        appendLine(dump, "compiledClassesTotal", compiledEntries.size());
        appendList(dump, "compiledClassSourceCounts", compiledClassSourceCounts(compiledEntries));
        appendLimitedList(dump, "compiledClasses", compiledEntries.stream()
                .map(GADebugOverlay::formatCompiledClassSummary)
                .toList(), DUMP_COMPILED_CLASS_LIMIT);
        appendLimitedRawList(dump, "compiledClassRootDetails", compiledEntries.stream()
                .map(GADebugOverlay::formatCompiledClassRootDetail)
                .toList(), DUMP_COMPILED_CLASS_LIMIT);
        appendLine(dump, "gpuEligibleRoots", routerStats.gpuEligibleRoots());
        appendLine(dump, "gpuBlockedRoots", routerStats.gpuBlockedRoots());
        appendLine(dump, "gpuBlockersTotal", routerStats.gpuBlockersTotal());
        appendLine(dump, "gpuPayloadReadyRoots", routerStats.gpuPayloadReadyRoots());
        appendLine(dump, "gpuPayloadBlockedRoots", routerStats.gpuPayloadBlockedRoots());
        appendLine(dump, "gpuPayloadNodesTotal", routerStats.gpuPayloadNodesTotal());
        appendLine(dump, "gpuPayloadParityChecks", routerStats.gpuPayloadParityChecks());
        appendLine(dump, "gpuPayloadParityPasses", routerStats.gpuPayloadParityPasses());
        appendLine(dump, "gpuPayloadParityFailures", routerStats.gpuPayloadParityFailures());
        appendLine(dump, "gpuPayloadParityPoints", routerStats.gpuPayloadParityPoints());
        appendLine(dump, "gpuPayloadParityMaxAbsError", routerStats.gpuPayloadParityMaxAbsError());
        appendLine(dump, "gpuPayloadParityFirstFailure", routerStats.gpuPayloadParityFirstFailure());
        appendList(dump, "gpuBlockerCounts", routerStats.gpuBlockerCounts());
        appendList(dump, "gpuPayloadUnsupportedCounts", routerStats.gpuPayloadUnsupportedCounts());
        appendLine(dump, "gpuRuntimeEnabled", Boolean.getBoolean(GpuPayloadBatchExecutor.GPU_ENABLED_PROPERTY));
        appendLine(dump, "gpuRuntimePreflightState", GpuPayloadBatchExecutor.preflightStateName());
        appendLine(dump, "gpuRuntimePersistentScopeEnabled", GpuPayloadBatchExecutor.persistentRuntimeScopeEnabled());
        appendLine(dump, "gpuRuntimePersistentScopeActive", GpuPayloadBatchExecutor.persistentRuntimeScopeActive());
        appendLine(dump, "gpuRuntimePreparedLauncher", GpuPayloadBatchExecutor.preparedLauncherEnabled());
        appendLine(dump, "gpuRuntimeDirectGeneratedLauncher", GpuPayloadBatchExecutor.directGeneratedLauncherEnabled());
        appendLine(dump, "gpuRuntimeApiLocation", GpuPayloadBatchExecutor.runtimeApiLocation());
        appendLine(dump, "gpuRuntimeStaticArgs", GpuPayloadBatchExecutor.preparedLauncherStaticArguments());
        appendLine(dump, "gpuRuntimeDynamicArgs", GpuPayloadBatchExecutor.preparedLauncherDynamicArguments());
        appendLine(dump, "gpuRuntimeDisabledReason", GpuPayloadBatchExecutor.disabledReason());
        appendLine(dump, "gpuRuntimePreflightReason", GpuPayloadBatchExecutor.preflightReason());
        appendLine(dump, "gpuRuntimeParityRemaining", GpuPayloadBatchExecutor.runtimeParityRemaining());
        appendLine(dump, "gpuRuntimeParityEpsilon", GpuPayloadBatchExecutor.runtimeParityEpsilon());
        appendLine(dump, "gpuRuntimeBatchRemaining", GpuPayloadBatchExecutor.runtimeBatchRemaining());
        appendLine(dump, "gpuRuntimeBatchMax", GpuPayloadBatchExecutor.runtimeBatchBudgetMax());
        appendLine(dump, "gpuRuntimeMinPoints", GpuPayloadBatchExecutor.runtimeMinPoints());
        appendLine(dump, "gpuRuntimeLockWaitNanos", GpuPayloadBatchExecutor.runtimeLockWaitNanos());
        appendLine(dump, "gpuRuntimeMicroBatchMax", GpuPayloadBatchExecutor.runtimeMicroBatchMax());
        appendLine(dump, "gpuRuntimeMicroBatchMin", GpuPayloadBatchExecutor.runtimeMicroBatchMin());
        appendLine(dump, "gpuRuntimeMicroBatchCollectNanos", GpuPayloadBatchExecutor.runtimeMicroBatchCollectNanos());
        appendLine(dump, "gpuRuntimeMicroBatchWaitNanos", GpuPayloadBatchExecutor.runtimeMicroBatchWaitNanos());
        appendLine(dump, "gpuRuntimeMicroBatchBackoffSingleStreak", GpuPayloadBatchExecutor.runtimeMicroBatchBackoffSingleStreak());
        appendLine(dump, "gpuRuntimeMicroBatchBackoffBusyStreak", GpuPayloadBatchExecutor.runtimeMicroBatchBackoffBusyStreak());
        appendLine(dump, "gpuRuntimeMicroBatchBackoffBatches", GpuPayloadBatchExecutor.runtimeMicroBatchBackoffBatches());
        appendList(dump, "gpuPayloadBatchRuntimeGateCounts", routerStats.gpuPayloadBatchRuntimeGateCounts());
        appendLine(dump, "gpuDebugProbeLastStatus", lastGpuProbeStatus);
        appendLine(dump, "gpuLargeBatchProbeLastStatus", lastGpuLargeBatchProbeStatus);
        appendLine(dump, "dfcDebugCompileLastStatus", lastDfcCompileProbeStatus);
        appendLine(dump, "gpuPayloadBatchAttempts", routerStats.gpuPayloadBatchAttempts());
        appendLine(dump, "gpuPayloadBatchGpuSuccesses", routerStats.gpuPayloadBatchGpuSuccesses());
        appendLine(dump, "gpuPayloadBatchCpuFallbacks", routerStats.gpuPayloadBatchCpuFallbacks());
        appendLine(dump, "gpuPayloadBatchPoints", routerStats.gpuPayloadBatchPoints());
        appendLine(dump, "gpuPayloadBatchGpuSuccessPoints", routerStats.gpuPayloadBatchGpuSuccessPoints());
        appendLine(dump, "gpuPayloadBatchCpuFallbackPoints", routerStats.gpuPayloadBatchCpuFallbackPoints());
        appendLine(dump, "gpuPayloadBatchExternNanos", routerStats.gpuPayloadBatchExternNanos());
        appendLine(dump, "gpuPayloadBatchInvokeNanos", routerStats.gpuPayloadBatchInvokeNanos());
        appendLine(dump, "gpuPayloadBatchColdInvokes", routerStats.gpuPayloadBatchColdInvokes());
        appendLine(dump, "gpuPayloadBatchColdInvokeNanos", routerStats.gpuPayloadBatchColdInvokeNanos());
        appendLine(dump, "gpuPayloadBatchWarmInvokes", routerStats.gpuPayloadBatchWarmInvokes());
        appendLine(dump, "gpuPayloadBatchWarmInvokeNanos", routerStats.gpuPayloadBatchWarmInvokeNanos());
        appendLine(dump, "gpuPayloadBatchRuntimeLockWaitNanos", routerStats.gpuPayloadBatchRuntimeLockWaitNanos());
        appendLine(dump, "gpuPayloadBatchRuntimeLockHeldNanos", routerStats.gpuPayloadBatchRuntimeLockHeldNanos());
        appendLine(dump, "gpuPayloadBatchRuntimeLockEntries", routerStats.gpuPayloadBatchRuntimeLockEntries());
        appendLine(dump, "gpuPayloadBatchRuntimeLockBusySkips", routerStats.gpuPayloadBatchRuntimeLockBusySkips());
        appendLine(dump, "gpuPayloadBatchMicroLaunches", routerStats.gpuPayloadBatchMicroLaunches());
        appendLine(dump, "gpuPayloadBatchMicroRequests", routerStats.gpuPayloadBatchMicroRequests());
        appendLine(dump, "gpuPayloadBatchMicroSlots", routerStats.gpuPayloadBatchMicroSlots());
        appendLine(dump, "gpuPayloadBatchMicroSingles", routerStats.gpuPayloadBatchMicroSingles());
        appendLine(dump, "gpuPayloadBatchMicroSkippedLaunches", routerStats.gpuPayloadBatchMicroSkippedLaunches());
        appendLine(dump, "gpuPayloadBatchMicroSkippedRequests", routerStats.gpuPayloadBatchMicroSkippedRequests());
        appendLine(dump, "gpuPayloadBatchRuntimeBackoffTriggers", routerStats.gpuPayloadBatchRuntimeBackoffTriggers());
        appendLine(dump, "gpuPayloadBatchRuntimeBackoffSkips", routerStats.gpuPayloadBatchRuntimeBackoffSkips());
        appendLine(dump, "gpuPayloadBatchRuntimeBackoffBatches", routerStats.gpuPayloadBatchRuntimeBackoffBatches());
        appendLine(dump, "gpuPayloadBatchPreparedCacheHits", routerStats.gpuPayloadBatchPreparedCacheHits());
        appendLine(dump, "gpuPayloadBatchPreparedCacheMisses", routerStats.gpuPayloadBatchPreparedCacheMisses());
        appendLine(dump, "gpuPayloadBatchStaticArgs", routerStats.gpuPayloadBatchStaticArgs());
        appendLine(dump, "gpuPayloadBatchDynamicArgs", routerStats.gpuPayloadBatchDynamicArgs());
        appendLine(dump, "gpuPayloadBatchPreparedTimingTotalNanos", routerStats.gpuPayloadBatchPreparedTimingTotalNanos());
        appendLine(dump, "gpuPayloadBatchPreparedBufferAllocateNanos", routerStats.gpuPayloadBatchPreparedBufferAllocateNanos());
        appendLine(dump, "gpuPayloadBatchPreparedBufferAllocateCount", routerStats.gpuPayloadBatchPreparedBufferAllocateCount());
        appendLine(dump, "gpuPayloadBatchPreparedBufferReuseNanos", routerStats.gpuPayloadBatchPreparedBufferReuseNanos());
        appendLine(dump, "gpuPayloadBatchPreparedBufferReuseCount", routerStats.gpuPayloadBatchPreparedBufferReuseCount());
        appendLine(dump, "gpuPayloadBatchPreparedUploadNanos", routerStats.gpuPayloadBatchPreparedUploadNanos());
        appendLine(dump, "gpuPayloadBatchPreparedUploadCount", routerStats.gpuPayloadBatchPreparedUploadCount());
        appendLine(dump, "gpuPayloadBatchPreparedUploadBytes", routerStats.gpuPayloadBatchPreparedUploadBytes());
        appendLine(dump, "gpuPayloadBatchPreparedSkippedUploadCount", routerStats.gpuPayloadBatchPreparedSkippedUploadCount());
        appendLine(dump, "gpuPayloadBatchPreparedSkippedUploadBytes", routerStats.gpuPayloadBatchPreparedSkippedUploadBytes());
        appendLine(dump, "gpuPayloadBatchPreparedBindNanos", routerStats.gpuPayloadBatchPreparedBindNanos());
        appendLine(dump, "gpuPayloadBatchPreparedBindCount", routerStats.gpuPayloadBatchPreparedBindCount());
        appendLine(dump, "gpuPayloadBatchPreparedEnqueueSubmitNanos", routerStats.gpuPayloadBatchPreparedEnqueueSubmitNanos());
        appendLine(dump, "gpuPayloadBatchPreparedEnqueueWaitNanos", routerStats.gpuPayloadBatchPreparedEnqueueWaitNanos());
        appendLine(dump, "gpuPayloadBatchPreparedQueueFinishNanos", routerStats.gpuPayloadBatchPreparedQueueFinishNanos());
        appendLine(dump, "gpuPayloadBatchPreparedReadbackNanos", routerStats.gpuPayloadBatchPreparedReadbackNanos());
        appendLine(dump, "gpuPayloadBatchPreparedReadbackCount", routerStats.gpuPayloadBatchPreparedReadbackCount());
        appendLine(dump, "gpuPayloadBatchPreparedReadbackBytes", routerStats.gpuPayloadBatchPreparedReadbackBytes());
        appendLine(dump, "gpuPayloadBatchParityNanos", routerStats.gpuPayloadBatchParityNanos());
        appendLine(dump, "gpuPayloadBatchTotalNanos", routerStats.gpuPayloadBatchTotalNanos());
        appendLine(dump, "gpuPayloadBatchFirstFallback", routerStats.gpuPayloadBatchFirstFallback());
        appendLine(dump, "gpuPayloadBatchRuntimeParityChecks", routerStats.gpuPayloadBatchRuntimeParityChecks());
        appendLine(dump, "gpuPayloadBatchRuntimeParityPasses", routerStats.gpuPayloadBatchRuntimeParityPasses());
        appendLine(dump, "gpuPayloadBatchRuntimeParityFailures", routerStats.gpuPayloadBatchRuntimeParityFailures());
        appendLine(dump, "gpuPayloadBatchRuntimeParityPoints", routerStats.gpuPayloadBatchRuntimeParityPoints());
        appendLine(dump, "gpuPayloadBatchRuntimeParityMaxAbsError", routerStats.gpuPayloadBatchRuntimeParityMaxAbsError());
        appendLine(dump, "gpuPayloadBatchRuntimeParityFirstFailure", routerStats.gpuPayloadBatchRuntimeParityFirstFailure());
        appendLine(dump, "lazyWrappersCreated", routerStats.lazyWrappersCreated());
        appendLine(dump, "lazyResolveAttempts", routerStats.lazyResolveAttempts());
        appendLine(dump, "lazySuccessfulCompiles", routerStats.lazySuccessfulCompiles());
        appendLine(dump, "lazyCompileFailures", routerStats.lazyCompileFailures());
        appendLine(dump, "lazyCompileFallbacks", routerStats.lazyCompileFallbacks());
        appendLine(dump, "mapAllIdentityNoOps", mapAllStats.identityNoOps());
        appendLine(dump, "mapAllRebinds", mapAllStats.rebinds());
        appendLine(dump, "mapAllSessions", sessionStats.sessionsCreated());
        appendLine(dump, "mapAllMemoHits", sessionStats.memoHits());
        appendLine(dump, "mapAllMemoMisses", sessionStats.memoMisses());
        appendLine(dump, "mapAllMaxMemoSize", sessionStats.maxMemoSize());
        appendLine(dump, "cacheFastPathEligible", cacheFastPathStats.eligibleCalls());
        appendLine(dump, "cacheFastPathHits", cacheFastPathStats.hits());
        appendLine(dump, "cacheFastPathMisses", cacheFastPathStats.misses());
        appendLine(dump, "cacheFastPathDisabledFallbacks", cacheFastPathStats.disabledFallbacks());
        appendLine(dump, "cacheFastPathNonAccessFallbacks", cacheFastPathStats.nonAccessFallbacks());

        if (shouldDumpCellFill(cellFillStats)) {
            appendSection(dump, "Cell Fill");
            appendLine(dump, "statsEnabled", cellFillStats.enabled());
            appendLine(dump, "cellScalar", cellFillStats.cellScalar());
            appendLine(dump, "cellCompiled", cellFillStats.cellCompiled());
            appendLine(dump, "cellUnknown", cellFillStats.cellUnknown());
            appendLine(dump, "cellXzSlab", cellFillStats.cellXzSlab());
            appendLine(dump, "cellExternAccumulate", cellFillStats.cellExternAccumulate());
            appendLine(dump, "cellExternScalarResidual", cellFillStats.cellExternScalarResidual());
            appendLine(dump, "cellGpuPayloadReady", cellFillStats.cellGpuPayloadReady());
            appendLine(dump, "cellGpuPayloadBlocked", cellFillStats.cellGpuPayloadBlocked());
            appendLine(dump, "columnsScalar", cellFillStats.columnsScalar());
            appendLine(dump, "columnsJavaBatched", cellFillStats.columnsJavaBatched());
            appendList(dump, "fastFillerClasses", cellFillStats.fastFillerClasses().stream()
                    .map(stat -> stat.className() + "=" + stat.calls())
                    .toList());
            appendList(dump, "fastFillerDebugClasses", cellFillStats.fastFillerDebugClasses().stream()
                    .map(stat -> stripHiddenClassSuffix(stat.className())
                            + "=" + stat.calls()
                            + "{source=" + simpleClassName(stat.sourceRootClass())
                            + ",lattice=" + stat.latticeEmitted()
                            + ",cellAddLattice=" + stat.cellAddLatticeSpecialized()
                            + ",cellAddBeardifier=" + stat.cellAddBeardifierSpecialized()
                            + ",cellAddExtern=" + stat.cellAddExternSpecialized()
                            + ",cellScalarMarker=" + stat.cellScalarMarkerSpecialized()
                            + ",root=" + stat.rootDebug()
                            + "}")
                    .toList());
            appendList(dump, "sourceFillerClasses", cellFillStats.sourceFillerClasses());
            appendList(dump, "residualExternFallbackClasses", cellFillStats.residualExternFallbackClasses());
            appendList(dump, "cellGpuFirstBlockers", cellFillStats.cellGpuFirstBlockers());
            appendList(dump, "cellGpuUnsupportedNodes", cellFillStats.cellGpuUnsupportedNodes());
        }

        if (shouldDumpCellFillParity(parityStats)) {
            appendSection(dump, "Cell Fill Parity");
            appendLine(dump, "enabled", parityStats.enabled());
            appendLine(dump, "checks", parityStats.checks());
            appendLine(dump, "passes", parityStats.passes());
            appendLine(dump, "failures", parityStats.failures());
            appendLine(dump, "skipped", parityStats.skipped());
            appendLine(dump, "candidates", parityStats.candidates());
            appendLine(dump, "fastEligible", parityStats.fastEligible());
            appendLine(dump, "lazyFastEligible", parityStats.lazyFastEligible());
            appendLine(dump, "fallbacks", parityStats.fallbacks());
            appendLine(dump, "remaining", parityStats.remaining());
            appendLine(dump, "maxChecks", parityStats.maxChecks());
            appendLine(dump, "epsilon", parityStats.epsilon());
            appendList(dump, "fallbackClasses", parityStats.fallbackClasses());
        }

        appendSection(dump, "NoiseChunk Timing");
        appendLine(dump, "enabled", noiseChunkTimingStats.enabled());
        appendLine(dump, "stageTimingEnabled", noiseChunkTimingStats.stageTimingEnabled());
        appendLine(dump, "fillSliceCalls", noiseChunkTimingStats.fillSliceCalls());
        appendLine(dump, "fillSliceTotalNanos", noiseChunkTimingStats.fillSliceTotalNanos());
        appendLine(dump, "fillSliceAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.fillSliceTotalNanos(), noiseChunkTimingStats.fillSliceCalls()));
        appendLine(dump, "fillSliceBatchSurfacePoints", noiseChunkTimingStats.fillSliceBatchSurfacePoints());
        appendLine(dump, "fillSliceBatchSurfaceAvgPoints", formatAverage(
                noiseChunkTimingStats.fillSliceBatchSurfacePoints(), noiseChunkTimingStats.fillSliceCalls()));
        appendLine(dump, "fillSliceBatchSurfaceMaxPoints", noiseChunkTimingStats.fillSliceBatchSurfaceMaxPoints());
        appendLine(dump, "fillSliceBatchSurfaceAvgColumns", formatAverage(
                noiseChunkTimingStats.fillSliceBatchSurfaceColumns(), noiseChunkTimingStats.fillSliceCalls()));
        appendLine(dump, "fillSliceBatchSurfaceAvgY", formatAverage(
                noiseChunkTimingStats.fillSliceBatchSurfaceY(), noiseChunkTimingStats.fillSliceCalls()));
        appendLine(dump, "fillSliceBatchSurfaceAvgInterpolators", formatAverage(
                noiseChunkTimingStats.fillSliceBatchSurfaceInterpolators(), noiseChunkTimingStats.fillSliceCalls()));
        appendLine(dump, "fillSlicePayloadRoots", noiseChunkTimingStats.fillSlicePayloadRoots());
        appendLine(dump, "fillSlicePayloadReadyRoots", noiseChunkTimingStats.fillSlicePayloadReadyRoots());
        appendLine(dump, "fillSlicePayloadExternRoots", noiseChunkTimingStats.fillSlicePayloadExternRoots());
        appendLine(dump, "fillSlicePayloadPoints", noiseChunkTimingStats.fillSlicePayloadPoints());
        appendLine(dump, "fillSlicePayloadReadyPoints", noiseChunkTimingStats.fillSlicePayloadReadyPoints());
        appendLine(dump, "fillSlicePayloadExternPoints", noiseChunkTimingStats.fillSlicePayloadExternPoints());
        appendLine(dump, "fillSliceLazyCompileAttempts", noiseChunkTimingStats.fillSliceLazyCompileAttempts());
        appendLine(dump, "fillSliceLazyCompileSuccesses", noiseChunkTimingStats.fillSliceLazyCompileSuccesses());
        appendLine(dump, "fillSliceLazyCompileFailures", noiseChunkTimingStats.fillSliceLazyCompileFailures());
        appendLine(dump, "fillSliceLazyCompileBudgetSkips", noiseChunkTimingStats.fillSliceLazyCompileBudgetSkips());
        appendLine(dump, "fillSliceGpuCandidateRoots", noiseChunkTimingStats.fillSliceGpuCandidateRoots());
        appendLine(dump, "fillSliceGpuBestGroupMaxRoots", noiseChunkTimingStats.fillSliceGpuBestGroupMaxRoots());
        appendLine(dump, "fillSliceGpuBestGroupMaxPoints", noiseChunkTimingStats.fillSliceGpuBestGroupMaxPoints());
        appendLine(dump, "fillSliceGpuGroupedLaunches", noiseChunkTimingStats.fillSliceGpuGroupedLaunches());
        appendLine(dump, "fillSliceGpuGroupedRoots", noiseChunkTimingStats.fillSliceGpuGroupedRoots());
        appendLine(dump, "fillSliceGpuGroupedPoints", noiseChunkTimingStats.fillSliceGpuGroupedPoints());
        appendList(dump, "fillSlicePayloadMissingClasses",
                compactGeneratedClassCounts(noiseChunkTimingStats.fillSlicePayloadMissingClasses()));
        appendList(dump, "fillSlicePayloadBlockedReasons",
                noiseChunkTimingStats.fillSlicePayloadBlockedReasons());
        appendLine(dump, "selectCellYzCalls", noiseChunkTimingStats.selectCellYzCalls());
        appendLine(dump, "selectCellYzTotalNanos", noiseChunkTimingStats.selectCellYzTotalNanos());
        appendLine(dump, "selectCellYzAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzTotalNanos(), noiseChunkTimingStats.selectCellYzCalls()));
        appendLine(dump, "selectCellYzSetupNanos", noiseChunkTimingStats.selectCellYzSetupNanos());
        appendLine(dump, "selectCellYzSetupAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzSetupNanos(), noiseChunkTimingStats.selectCellYzCalls()));
        appendLine(dump, "selectCellYzCacheFillNanos", noiseChunkTimingStats.selectCellYzCacheFillNanos());
        appendLine(dump, "selectCellYzCacheFillAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzCacheFillNanos(), noiseChunkTimingStats.selectCellYzCalls()));
        appendLine(dump, "selectCellYzFastFillCalls", noiseChunkTimingStats.selectCellYzFastFillCalls());
        appendLine(dump, "selectCellYzFastFillNanos", noiseChunkTimingStats.selectCellYzFastFillNanos());
        appendLine(dump, "selectCellYzFastFillAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzFastFillNanos(), noiseChunkTimingStats.selectCellYzFastFillCalls()));
        appendLine(dump, "selectCellYzFallbackFillCalls", noiseChunkTimingStats.selectCellYzFallbackFillCalls());
        appendLine(dump, "selectCellYzFallbackFillNanos", noiseChunkTimingStats.selectCellYzFallbackFillNanos());
        appendLine(dump, "selectCellYzFallbackFillAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzFallbackFillNanos(), noiseChunkTimingStats.selectCellYzFallbackFillCalls()));
        appendLine(dump, "selectCellYzLazyResolveCalls", noiseChunkTimingStats.selectCellYzLazyResolveCalls());
        appendLine(dump, "selectCellYzLazyResolveNanos", noiseChunkTimingStats.selectCellYzLazyResolveNanos());
        appendLine(dump, "selectCellYzLazyResolveAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzLazyResolveNanos(), noiseChunkTimingStats.selectCellYzLazyResolveCalls()));
        appendLine(dump, "selectCellYzAp2PrimaryCalls", noiseChunkTimingStats.selectCellYzAp2PrimaryCalls());
        appendLine(dump, "selectCellYzAp2PrimaryNanos", noiseChunkTimingStats.selectCellYzAp2PrimaryNanos());
        appendLine(dump, "selectCellYzAp2PrimaryAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzAp2PrimaryNanos(), noiseChunkTimingStats.selectCellYzAp2PrimaryCalls()));
        appendLine(dump, "selectCellYzAp2SecondaryCalls", noiseChunkTimingStats.selectCellYzAp2SecondaryCalls());
        appendLine(dump, "selectCellYzAp2SecondaryNanos", noiseChunkTimingStats.selectCellYzAp2SecondaryNanos());
        appendLine(dump, "selectCellYzAp2SecondaryAvgNanos", formatAverageNanos(
                noiseChunkTimingStats.selectCellYzAp2SecondaryNanos(), noiseChunkTimingStats.selectCellYzAp2SecondaryCalls()));
        appendLine(dump, "selectCellYzAp2ZeroSecondarySkips", noiseChunkTimingStats.selectCellYzAp2ZeroSecondarySkips());
        appendList(dump, "selectCellYzFastFillerClasses",
                noiseChunkTimingStats.selectCellYzFastFillerClasses());
        appendList(dump, "selectCellYzFastFillerDetails",
                noiseChunkTimingStats.selectCellYzFastFillerDetails());
        appendList(dump, "selectCellYzFallbackFillerClasses",
                noiseChunkTimingStats.selectCellYzFallbackFillerClasses());
        appendList(dump, "selectCellYzFallbackFillerDetails",
                noiseChunkTimingStats.selectCellYzFallbackFillerDetails());

        if (shouldDumpSpline(splineStats)) {
            appendSection(dump, "Spline Runtime");
            appendLine(dump, "enabled", splineStats.enabled());
            appendLine(dump, "searchMode", Codegen.splineSearchModeName());
            appendLine(dump, "linearMaxPoints", Codegen.SPLINE_LINEAR_SEARCH_MAX_POINTS);
            appendLine(dump, "segmentLutEnabled", Codegen.SPLINE_SEGMENT_LUT_ENABLED);
            appendLine(dump, "segmentLutMinPoints", Codegen.SPLINE_SEGMENT_LUT_MIN_POINTS);
            appendLine(dump, "segmentLutBuckets", Codegen.SPLINE_SEGMENT_LUT_BUCKETS);
            appendLine(dump, "calls", splineStats.calls());
            appendLine(dump, "linearCalls", splineStats.linearCalls());
            appendLine(dump, "binaryCalls", splineStats.binaryCalls());
            appendLine(dump, "lutCalls", splineStats.lutCalls());
            appendLine(dump, "interiorCalls", splineStats.interiorCalls());
            appendLine(dump, "leftExtrapolationCalls", splineStats.leftExtrapolationCalls());
            appendLine(dump, "rightExtrapolationCalls", splineStats.rightExtrapolationCalls());
            appendLine(dump, "totalNanos", splineStats.totalNanos());
            appendLine(dump, "linearNanos", splineStats.linearNanos());
            appendLine(dump, "binaryNanos", splineStats.binaryNanos());
            appendLine(dump, "lutNanos", splineStats.lutNanos());
            appendLine(dump, "bucketLe2", formatBucket(splineStats.bucketLe2()));
            appendLine(dump, "bucket3To4", formatBucket(splineStats.bucket3To4()));
            appendLine(dump, "bucket5To8", formatBucket(splineStats.bucket5To8()));
            appendLine(dump, "bucketGe9", formatBucket(splineStats.bucketGe9()));
            appendList(dump, "topSplineClasses", topSplineClasses.stream()
                    .map(stat -> stat.className()
                            + "{source=" + stat.sourceRootClass()
                            + ", root=" + stat.rootDebug()
                            + ", spline=" + stat.splineDebug()
                            + ", calls=" + stat.calls()
                            + ", totalMs=" + formatMillis(stat.totalNanos())
                            + ", avgNs=" + formatAverageNanos(stat.totalNanos(), stat.calls())
                            + ", linear=" + stat.linearCalls()
                            + ", binary=" + stat.binaryCalls()
                            + ", lut=" + stat.lutCalls()
                            + ", interior=" + stat.interiorCalls()
                            + ", left=" + stat.leftExtrapolationCalls()
                            + ", right=" + stat.rightExtrapolationCalls()
                            + ", point3=" + formatBucket(stat.point3())
                            + ", point4=" + formatBucket(stat.point4())
                            + ", <=2=" + formatBucket(stat.bucketLe2())
                            + ", 3..4=" + formatBucket(stat.bucket3To4())
                            + ", 5..8=" + formatBucket(stat.bucket5To8())
                            + ", >=9=" + formatBucket(stat.bucketGe9())
                            + "}")
                    .toList());
        }

        if (shouldDumpAquifer(aquiferStats)) {
            appendSection(dump, "Aquifer");
            appendLine(dump, "computeSubstanceCalls", aquiferStats.computeSubstanceCalls());
            appendLine(dump, "positiveDensityReturns", aquiferStats.positiveDensityReturns());
            appendLine(dump, "globalLavaReturns", aquiferStats.globalLavaReturns());
            appendLine(dump, "refreshDistCalls", aquiferStats.refreshDistCalls());
            appendLine(dump, "barrierNoiseComputes", aquiferStats.barrierNoiseComputes());
            appendLine(dump, "waterBelowLavaReturns", aquiferStats.waterBelowLavaReturns());
            appendLine(dump, "pressureAbortReturns", aquiferStats.pressureAbortReturns());
            appendLine(dump, "finalSolidReturns", aquiferStats.finalSolidReturns());
            appendLine(dump, "lazyThirdResolves", aquiferStats.lazyThirdResolves());
            appendLine(dump, "refreshDistTimedCalls", aquiferStats.refreshDistTimedCalls());
            appendLine(dump, "refreshDistTotalNanos", aquiferStats.refreshDistTotalNanos());
            appendLine(dump, "lazyThirdTimedCalls", aquiferStats.lazyThirdTimedCalls());
            appendLine(dump, "lazyThirdTotalNanos", aquiferStats.lazyThirdTotalNanos());
            appendLine(dump, "aquiferStatusTimedCalls", aquiferStats.aquiferStatusTimedCalls());
            appendLine(dump, "aquiferStatusTotalNanos", aquiferStats.aquiferStatusTotalNanos());
        }

        if (shouldDumpBeardifier(beardifierStats)) {
            appendSection(dump, "Beardifier");
            appendLine(dump, "enabled", beardifierStats.enabled());
            appendLine(dump, "computeCellCalls", beardifierStats.computeCellCalls());
            appendLine(dump, "computeCellSingleCalls", beardifierStats.computeCellSingleCalls());
            appendLine(dump, "computeCellBulkLogicalCalls", beardifierStats.computeCellBulkLogicalCalls());
            appendLine(dump, "fillCellCalls", beardifierStats.fillCellCalls());
            appendLine(dump, "accumulateCellCalls", beardifierStats.accumulateCellCalls());
            appendLine(dump, "cellActivePieces", beardifierStats.cellActivePieces());
            appendLine(dump, "cellActiveJunctions", beardifierStats.cellActiveJunctions());
            appendLine(dump, "outsideInfluenceReturns", beardifierStats.outsideInfluenceReturns());
            appendLine(dump, "outsideCellCacheHits", beardifierStats.outsideCellCacheHits());
            appendLine(dump, "emptyActiveReturns", beardifierStats.emptyActiveReturns());
            appendLine(dump, "columnsProcessed", beardifierStats.columnsProcessed());
            appendLine(dump, "columnCacheHits", beardifierStats.columnCacheHits());
            appendLine(dump, "directComputeFallbacks", beardifierStats.directComputeFallbacks());
            appendLine(dump, "emptyColumnsAfterFilter", beardifierStats.emptyColumnsAfterFilter());
            appendLine(dump, "columnPiecesBeforeFilter", beardifierStats.columnPiecesBeforeFilter());
            appendLine(dump, "columnPiecesAfterFilter", beardifierStats.columnPiecesAfterFilter());
            appendLine(dump, "columnJunctionsBeforeFilter", beardifierStats.columnJunctionsBeforeFilter());
            appendLine(dump, "columnJunctionsAfterFilter", beardifierStats.columnJunctionsAfterFilter());
            appendLine(dump, "filteredBuryPieces", beardifierStats.filteredBuryPieces());
            appendLine(dump, "filteredThinPieces", beardifierStats.filteredThinPieces());
            appendLine(dump, "filteredBoxPieces", beardifierStats.filteredBoxPieces());
            appendLine(dump, "filteredEncapsulatePieces", beardifierStats.filteredEncapsulatePieces());
            appendLine(dump, "computeCellTimedCalls", beardifierStats.computeCellTimedCalls());
            appendLine(dump, "computeCellTotalNanos", beardifierStats.computeCellTotalNanos());
            appendLine(dump, "fillCellTimedCalls", beardifierStats.fillCellTimedCalls());
            appendLine(dump, "fillCellTotalNanos", beardifierStats.fillCellTotalNanos());
            appendLine(dump, "accumulateCellTimedCalls", beardifierStats.accumulateCellTimedCalls());
            appendLine(dump, "accumulateCellTotalNanos", beardifierStats.accumulateCellTotalNanos());
            appendLine(dump, "rebuildColumnTimedCalls", beardifierStats.rebuildColumnTimedCalls());
            appendLine(dump, "rebuildColumnTotalNanos", beardifierStats.rebuildColumnTotalNanos());
            appendLine(dump, "directComputeTimedCalls", beardifierStats.directComputeTimedCalls());
            appendLine(dump, "directComputeTotalNanos", beardifierStats.directComputeTotalNanos());
        }

        if (shouldDumpRegistryWarmer(registryWarmerStats)) {
            appendSection(dump, "Registry Warmer");
            appendLine(dump, "calls", registryWarmerStats.calls());
            appendLine(dump, "skippedDuplicateCalls", registryWarmerStats.skippedDuplicateCalls());
            appendLine(dump, "skippedDuplicateEntries", registryWarmerStats.skippedDuplicateEntries());
            appendLine(dump, "warmedRouters", registryWarmerStats.warmedRouters());
            appendLine(dump, "warmedDensityFunctions", registryWarmerStats.warmedDensityFunctions());
            appendLine(dump, "failedEntries", registryWarmerStats.failedEntries());
            appendLine(dump, "budgetSkips", registryWarmerStats.budgetSkips());
        }
        return dump.toString();
    }

    private static String buildBiomeDump() {
        StringBuilder dump = new StringBuilder(1024);
        Minecraft minecraft = Minecraft.getInstance();
        FlatClimateIndex.Stats stats = FlatClimateIndex.snapshotStats();

        appendSection(dump, "Environment");
        appendLine(dump, "devMode", GeneratorAccelerator.isDevMode());
        appendLine(dump, "platform", GeneratorAccelerator.getPlatform());
        appendLine(dump, "gameDir", GeneratorAccelerator.getGameFolder());
        appendLine(dump, "fps", minecraft.getFps());
        appendLine(dump, "levelLoaded", minecraft.level != null);
        appendLine(dump, "playerLoaded", minecraft.player != null);
        appendLine(dump, "screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());

        appendSection(dump, "Biome Climate");
        appendLine(dump, "indexBuilds", stats.indexBuilds());
        appendLine(dump, "lastNodeCount", stats.lastNodeCount());
        appendLine(dump, "lastLeafCount", stats.lastLeafCount());
        appendLine(dump, "lastValueCount", stats.lastValueCount());
        appendLine(dump, "lastBoundsBytes", stats.lastBoundsBytes());
        appendLine(dump, "activeDimensionMask", "0x" + Integer.toHexString(stats.activeDimensionMask()));
        appendLine(dump, "fullQueryDimensions", stats.fullQueryDimensions());
        appendLine(dump, "hasOffsetDistances", stats.hasOffsetDistances());
        appendLine(dump, "linearSearchIndex", stats.linearSearchIndex());
        appendLine(dump, "linearSearchThreshold", stats.linearSearchThreshold());
        appendLine(dump, "adaptiveQueryCache", stats.adaptiveQueryCache());
        appendLine(dump, "queryCacheSize", stats.queryCacheSize());
        appendLine(dump, "queryCacheDisableProbes", stats.queryCacheDisableProbes());
        appendLine(dump, "queryCacheDisableHitRateShift", stats.queryCacheDisableHitRateShift());
        appendLine(dump, "noOffsetCapOrder", stats.noOffsetCapOrder());
        appendLine(dump, "searches", stats.searches());
        appendLine(dump, "lastValueCacheHits", stats.lastValueCacheHits());
        appendLine(dump, "queryCacheProbes", stats.queryCacheProbes());
        appendLine(dump, "queryCacheHits", stats.queryCacheHits());
        appendLine(dump, "queryCacheDisables", stats.queryCacheDisables());
        appendLine(dump, "linearSearchCalls", stats.linearSearchCalls());
        appendLine(dump, "treeSearchCalls", stats.treeSearchCalls());
        appendLine(dump, "warmStartZeroHits", stats.warmStartZeroHits());
        appendLine(dump, "secondWarmStartZeroHits", stats.secondWarmStartZeroHits());
        appendLine(dump, "treeNodeVisits", stats.treeNodeVisits());
        appendLine(dump, "treeChildDistanceTests", stats.treeChildDistanceTests());
        appendLine(dump, "treeChildAccepts", stats.treeChildAccepts());
        appendLine(dump, "treeValidChildren0", stats.treeValidChildren0());
        appendLine(dump, "treeValidChildren1", stats.treeValidChildren1());
        appendLine(dump, "treeValidChildren2", stats.treeValidChildren2());
        appendLine(dump, "treeValidChildren3", stats.treeValidChildren3());
        appendLine(dump, "treeValidChildren4", stats.treeValidChildren4());
        appendLine(dump, "treeValidChildren5", stats.treeValidChildren5());
        appendLine(dump, "treeValidChildren6", stats.treeValidChildren6());
        appendLine(dump, "treeValidChildren3Plus", stats.treeValidChildren3Plus());
        appendLine(dump, "linearLeafTests", stats.linearLeafTests());
        appendLine(dump, "noOffsetCapExitT", stats.noOffsetCapExitT());
        appendLine(dump, "noOffsetCapExitH", stats.noOffsetCapExitH());
        appendLine(dump, "noOffsetCapExitC", stats.noOffsetCapExitC());
        appendLine(dump, "noOffsetCapExitE", stats.noOffsetCapExitE());
        appendLine(dump, "noOffsetCapExitD", stats.noOffsetCapExitD());
        appendLine(dump, "noOffsetCapExitW", stats.noOffsetCapExitW());
        appendLine(dump, "noOffsetCapNoEarlyExit", stats.noOffsetCapNoEarlyExit());
        return dump.toString();
    }

    private static Path writeDumpToFile(DebugTab tab, String dump) throws IOException {
        Path debugDir = GeneratorAccelerator.getGameFolder().resolve("debug");
        Files.createDirectories(debugDir);
        String prefix = tab == DebugTab.BIOME ? "ga-debug-biome-" : "ga-debug-dfc-";
        Path dumpFile = debugDir.resolve(prefix + LocalDateTime.now().format(DUMP_TIMESTAMP) + ".txt");
        Files.writeString(dumpFile, dump, StandardCharsets.UTF_8);
        return dumpFile;
    }

    private static void appendSection(StringBuilder dump, String title) {
        if (!dump.isEmpty()) {
            dump.append(System.lineSeparator());
        }
        dump.append("=== ").append(title).append(" ===").append(System.lineSeparator());
    }

    private static void appendLine(StringBuilder dump, String key, Object value) {
        dump.append(key).append(": ").append(value).append(System.lineSeparator());
    }

    private static List<String> compiledClassSourceCounts(List<DfcCompiledClassRegistry.Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DfcCompiledClassRegistry.Entry entry : entries) {
            counts.merge(simpleClassName(entry.sourceRootClass()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
    }

    private static List<String> compactGeneratedClassCounts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int generatedCompiledCount = 0;
        for (String value : values) {
            int count = trailingCount(value);
            String key = countKey(value);
            if (key.contains(".CompiledDF_") || key.contains("CompiledDF_")) {
                generatedCompiledCount += count;
            } else {
                counts.merge(stripHiddenClassSuffix(key), count, Integer::sum);
            }
        }
        if (generatedCompiledCount > 0) {
            counts.put("CompiledDF_runtime_classes", generatedCompiledCount);
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
    }

    private static String countKey(String value) {
        if (value == null) {
            return "unknown";
        }
        int equals = value.lastIndexOf('=');
        return equals > 0 ? value.substring(0, equals) : value;
    }

    private static int trailingCount(String value) {
        if (value == null) {
            return 1;
        }
        int equals = value.lastIndexOf('=');
        if (equals < 0 || equals + 1 >= value.length()) {
            return 1;
        }
        try {
            return Integer.parseInt(value.substring(equals + 1));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String formatCompiledClassSummary(DfcCompiledClassRegistry.Entry entry) {
        StringBuilder out = new StringBuilder(160);
        out.append(simpleClassName(entry.classBaseName()))
                .append("{source=").append(simpleClassName(entry.sourceRootClass()))
                .append(", lattice=").append(entry.latticeEmitted());
        if (entry.cellAddLatticeSpecialized()) {
            out.append(", cellAddLattice=true");
        }
        if (entry.cellAddBeardifierSpecialized()) {
            out.append(", cellAddBeardifier=true");
        }
        if (entry.cellAddExternSpecialized()) {
            out.append(", cellAddExtern=true");
        }
        if (entry.cellScalarMarkerSpecialized()) {
            out.append(", cellScalarMarker=true");
        } else if (entry.cellScalarMarkerReason() != null && !entry.cellScalarMarkerReason().isBlank()) {
            out.append(", cellScalarMarker=").append(entry.cellScalarMarkerReason());
        }
        out.append(", root=").append(rootKind(entry.rootDebug())).append("}");
        return out.toString();
    }

    private static String formatCompiledClassRootDetail(DfcCompiledClassRegistry.Entry entry) {
        return simpleClassName(entry.classBaseName())
                + "{source=" + simpleClassName(entry.sourceRootClass())
                + ",cellScalarMarker=" + entry.cellScalarMarkerReason()
                + ",root=" + stripHiddenClassSuffix(entry.rootDebug())
                + "}";
    }

    private static String rootKind(String rootDebug) {
        if (rootDebug == null || rootDebug.isBlank()) {
            return "unknown";
        }
        int comma = rootDebug.indexOf(',');
        return comma >= 0 ? rootDebug.substring(0, comma) : rootDebug;
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "unknown";
        }
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static String truncateDumpValue(String value) {
        if (value == null) {
            return "null";
        }
        String normalized = stripHiddenClassSuffix(value);
        if (normalized.length() <= DUMP_VALUE_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, DUMP_VALUE_MAX_CHARS) + "...";
    }

    private static String stripHiddenClassSuffix(String value) {
        return value == null ? null : value.replaceAll("/0x[0-9a-fA-F]+", "");
    }

    private static boolean shouldDumpCellFill(DfcCellFillStats.Stats stats) {
        return stats.enabled()
                || stats.cellScalar() != 0L
                || stats.cellCompiled() != 0L
                || stats.cellUnknown() != 0L
                || stats.cellXzSlab() != 0L
                || stats.cellExternAccumulate() != 0L
                || stats.cellExternScalarResidual() != 0L
                || stats.cellGpuPayloadReady() != 0L
                || stats.cellGpuPayloadBlocked() != 0L
                || stats.columnsScalar() != 0L
                || stats.columnsJavaBatched() != 0L
                || !stats.fastFillerClasses().isEmpty()
                || !stats.sourceFillerClasses().isEmpty()
                || !stats.residualExternFallbackClasses().isEmpty()
                || !stats.cellGpuFirstBlockers().isEmpty()
                || !stats.cellGpuUnsupportedNodes().isEmpty();
    }

    private static boolean shouldDumpCellFillParity(DfcCellFillParity.Stats stats) {
        return stats.enabled()
                || stats.checks() != 0L
                || stats.passes() != 0L
                || stats.failures() != 0L
                || stats.skipped() != 0L
                || stats.candidates() != 0L
                || stats.fastEligible() != 0L
                || stats.lazyFastEligible() != 0L
                || stats.fallbacks() != 0L
                || !stats.fallbackClasses().isEmpty();
    }

    private static boolean shouldDumpSpline(DfcSplineStats.Stats stats) {
        return stats.enabled()
                || stats.calls() != 0L
                || stats.linearCalls() != 0L
                || stats.binaryCalls() != 0L
                || stats.lutCalls() != 0L;
    }

    private static boolean shouldDumpAquifer(AquiferStats.Stats stats) {
        return stats.computeSubstanceCalls() != 0L
                || stats.refreshDistCalls() != 0L
                || stats.barrierNoiseComputes() != 0L
                || stats.refreshDistTimedCalls() != 0L
                || stats.lazyThirdTimedCalls() != 0L
                || stats.aquiferStatusTimedCalls() != 0L;
    }

    private static boolean shouldDumpBeardifier(BeardifierStats.Stats stats) {
        return stats.enabled()
                || stats.computeCellCalls() != 0L
                || stats.computeCellSingleCalls() != 0L
                || stats.computeCellBulkLogicalCalls() != 0L
                || stats.fillCellCalls() != 0L
                || stats.accumulateCellCalls() != 0L
                || stats.columnsProcessed() != 0L
                || stats.directComputeFallbacks() != 0L;
    }

    private static boolean shouldDumpRegistryWarmer(RegistryWarmer.Stats stats) {
        return stats.calls() != 0L
                || stats.skippedDuplicateCalls() != 0L
                || stats.skippedDuplicateEntries() != 0L
                || stats.warmedRouters() != 0L
                || stats.warmedDensityFunctions() != 0L
                || stats.failedEntries() != 0L
                || stats.budgetSkips() != 0L;
    }

    private static void appendList(StringBuilder dump, String key, List<String> values) {
        appendLimitedList(dump, key, values, DUMP_LIST_LIMIT);
    }

    private static void appendLimitedList(StringBuilder dump, String key, List<String> values, int maxItems) {
        if (values == null || values.isEmpty()) {
            return;
        }
        dump.append(key).append(":").append(System.lineSeparator());
        int limit = Math.max(1, maxItems);
        int emitted = Math.min(values.size(), limit);
        for (int i = 0; i < emitted; i++) {
            dump.append("- ").append(truncateDumpValue(values.get(i))).append(System.lineSeparator());
        }
        if (values.size() > emitted) {
            dump.append("- ... ").append(values.size() - emitted).append(" more omitted")
                    .append(System.lineSeparator());
        }
    }

    private static void appendLimitedRawList(StringBuilder dump, String key, List<String> values, int maxItems) {
        if (values == null || values.isEmpty()) {
            return;
        }
        dump.append(key).append(":").append(System.lineSeparator());
        int limit = Math.max(1, maxItems);
        int emitted = Math.min(values.size(), limit);
        for (int i = 0; i < emitted; i++) {
            dump.append("- ").append(values.get(i)).append(System.lineSeparator());
        }
        if (values.size() > emitted) {
            dump.append("- ... ").append(values.size() - emitted).append(" more omitted")
                    .append(System.lineSeparator());
        }
    }
}
