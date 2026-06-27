package dev.sixik.generator_accelerator.debug;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.MapAllSession;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTreeNodeFlags;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class GADebugOverlay {
    private static final DateTimeFormatter DUMP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static boolean visible;
    private static String actionStatus = "";

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
        drawEnvironment(minecraft);
        ImGui.separator();
        drawRouterPipeline();
        ImGui.separator();
        drawCellFill();
        ImGui.separator();
        drawSplineStats();
        ImGui.separator();
        drawRegistryWarmer();
        ImGui.end();
    }

    private static void drawDumpActions() {
        if (ImGui.button("Copy Dump")) {
            String dump = buildDump();
            ImGui.setClipboardText(dump);
            actionStatus = "Copied debug dump to clipboard (" + dump.length() + " chars).";
        }

        ImGui.sameLine();
        if (ImGui.button("Save Dump")) {
            try {
                Path dumpFile = writeDumpToFile(buildDump());
                actionStatus = "Saved debug dump to " + dumpFile;
            } catch (IOException exception) {
                actionStatus = "Failed to save dump: " + exception.getMessage();
                GeneratorAccelerator.LOGGER.warn("Failed to save GA debug dump", exception);
            }
        }

        if (!actionStatus.isBlank()) {
            ImGui.textWrapped(actionStatus);
        }
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
        CompiledDensityFunction.MapAllStats mapAllStats = CompiledDensityFunction.snapshotMapAllStats();
        MapAllSession.Stats sessionStats = MapAllSession.snapshotStats();
        DfcCacheFastPath.Stats cacheFastPathStats = DfcCacheFastPath.snapshotStats();

        ImGui.text("Roots compiled: " + stats.rootsCompiled());
        ImGui.text("Classes alive: " + stats.classesAlive());
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
        DfcNativePlanningStats.Stats nativePlanningStats = DfcNativePlanningStats.snapshot();

        ImGui.text("Cell fill stats enabled: " + cellFillStats.enabled());
        ImGui.text("Compiled/scalar/unknown: " + cellFillStats.cellCompiled() + "/"
                + cellFillStats.cellScalar() + "/" + cellFillStats.cellUnknown());
        ImGui.text("Native slab inner: " + cellFillStats.cellNativeSlabInner());
        ImGui.text("XZ slab columns: " + cellFillStats.cellXzSlab());
        ImGui.text("Columns scalar/java/native: " + cellFillStats.columnsScalar() + "/"
                + cellFillStats.columnsJavaBatched() + "/" + cellFillStats.columnsNativeInner());
        ImGui.text("Extern accumulate/scalar residual: " + cellFillStats.cellExternAccumulate()
                + "/" + cellFillStats.cellExternScalarResidual());

        drawStringList("Fast filler classes", cellFillStats.fastFillerClasses().stream()
                .map(stat -> stat.className() + " = " + stat.calls() + " / native=" + stat.nativeSlabInnerCalls())
                .toList());
        drawStringList("Source filler classes", cellFillStats.sourceFillerClasses());
        drawStringList("Residual extern fallback classes", cellFillStats.residualExternFallbackClasses());

        ImGui.separator();
        ImGui.text("Parity enabled: " + parityStats.enabled());
        ImGui.text("Parity checks/pass/fail/skip: " + parityStats.checks() + "/"
                + parityStats.passes() + "/" + parityStats.failures() + "/" + parityStats.skipped());
        ImGui.text("Parity candidates/fast/lazy/fallbacks: " + parityStats.candidates() + "/"
                + parityStats.fastEligible() + "/" + parityStats.lazyFastEligible() + "/" + parityStats.fallbacks());
        ImGui.text("Parity remaining: " + parityStats.remaining() + " / " + parityStats.maxChecks());
        ImGui.text("Parity epsilon: " + parityStats.epsilon());
        drawStringList("Parity fallback classes", parityStats.fallbackClasses());

        ImGui.separator();
        ImGui.text("Native planning lattice roots: " + nativePlanningStats.latticeRoots());
        ImGui.text("Native ops disabled: " + nativePlanningStats.nativeOpsDisabled());
        ImGui.text("Slab plan present/missing: " + nativePlanningStats.slabPlanPresent() + "/"
                + nativePlanningStats.slabPlanMissing());
        ImGui.text("Slab missing no-slots/unsafe/bad-handle: " + nativePlanningStats.slabPlanMissingNoSlots() + "/"
                + nativePlanningStats.slabPlanMissingUnsafeCoords() + "/" + nativePlanningStats.slabPlanMissingBadHandleIndex());
        ImGui.text("Slab inner vm present/missing: " + nativePlanningStats.slabInnerVmPresent() + "/"
                + nativePlanningStats.slabInnerVmMissing());
        ImGui.text("Slab inner missing extracted/unsupported/invalid/io: "
                + nativePlanningStats.slabInnerVmMissingExtracted() + "/"
                + nativePlanningStats.slabInnerVmMissingUnsupportedNode() + "/"
                + nativePlanningStats.slabInnerVmMissingInvalidProgram() + "/"
                + nativePlanningStats.slabInnerVmMissingIo());
        ImGui.text("Axis Y/XZ: " + nativePlanningStats.axisYOnly() + "/" + nativePlanningStats.axisXzOnly());
        drawStringList("Unsupported slab-inner classes", nativePlanningStats.slabInnerUnsupportedClasses());
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

    private static String buildDump() {
        StringBuilder dump = new StringBuilder(4096);
        Minecraft minecraft = Minecraft.getInstance();
        RouterPipeline.Stats routerStats = RouterPipeline.snapshotStats();
        CompiledDensityFunction.MapAllStats mapAllStats = CompiledDensityFunction.snapshotMapAllStats();
        MapAllSession.Stats sessionStats = MapAllSession.snapshotStats();
        DfcCacheFastPath.Stats cacheFastPathStats = DfcCacheFastPath.snapshotStats();
        DfcCellFillStats.Stats cellFillStats = DfcCellFillStats.snapshot();
        DfcCellFillParity.Stats parityStats = DfcCellFillParity.snapshotStats();
        DfcNativePlanningStats.Stats nativePlanningStats = DfcNativePlanningStats.snapshot();
        DfcSplineStats.Stats splineStats = DfcSplineStats.snapshot();
        RegistryWarmer.Stats registryWarmerStats = RegistryWarmer.snapshotStats();
        List<DfcSplineStats.ClassStats> topSplineClasses = DfcSplineStats.snapshotTopClasses(5);

        appendSection(dump, "Environment");
        appendLine(dump, "devMode", GeneratorAccelerator.isDevMode());
        appendLine(dump, "platform", GeneratorAccelerator.getPlatform());
        appendLine(dump, "gameDir", GeneratorAccelerator.getGameFolder());
        appendLine(dump, "fps", minecraft.getFps());
        appendLine(dump, "levelLoaded", minecraft.level != null);
        appendLine(dump, "playerLoaded", minecraft.player != null);
        appendLine(dump, "screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());

        appendSection(dump, "Router Pipeline");
        appendLine(dump, "rootsCompiled", routerStats.rootsCompiled());
        appendLine(dump, "classesAlive", routerStats.classesAlive());
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

        appendSection(dump, "Cell Fill");
        appendLine(dump, "enabled", cellFillStats.enabled());
        appendLine(dump, "cellScalar", cellFillStats.cellScalar());
        appendLine(dump, "cellCompiled", cellFillStats.cellCompiled());
        appendLine(dump, "cellNativeSlabInner", cellFillStats.cellNativeSlabInner());
        appendLine(dump, "cellUnknown", cellFillStats.cellUnknown());
        appendLine(dump, "cellXzSlab", cellFillStats.cellXzSlab());
        appendLine(dump, "cellExternAccumulate", cellFillStats.cellExternAccumulate());
        appendLine(dump, "cellExternScalarResidual", cellFillStats.cellExternScalarResidual());
        appendLine(dump, "columnsScalar", cellFillStats.columnsScalar());
        appendLine(dump, "columnsJavaBatched", cellFillStats.columnsJavaBatched());
        appendLine(dump, "columnsNativeInner", cellFillStats.columnsNativeInner());
        appendList(dump, "fastFillerClasses", cellFillStats.fastFillerClasses().stream()
                .map(stat -> stat.className() + "=" + stat.calls() + "/native=" + stat.nativeSlabInnerCalls())
                .toList());
        appendList(dump, "sourceFillerClasses", cellFillStats.sourceFillerClasses());
        appendList(dump, "residualExternFallbackClasses", cellFillStats.residualExternFallbackClasses());

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

        appendSection(dump, "Native Planning");
        appendLine(dump, "latticeRoots", nativePlanningStats.latticeRoots());
        appendLine(dump, "nativeOpsDisabled", nativePlanningStats.nativeOpsDisabled());
        appendLine(dump, "slabPlanPresent", nativePlanningStats.slabPlanPresent());
        appendLine(dump, "slabPlanMissing", nativePlanningStats.slabPlanMissing());
        appendLine(dump, "slabPlanMissingNoSlots", nativePlanningStats.slabPlanMissingNoSlots());
        appendLine(dump, "slabPlanMissingUnsafeCoords", nativePlanningStats.slabPlanMissingUnsafeCoords());
        appendLine(dump, "slabPlanMissingBadHandleIndex", nativePlanningStats.slabPlanMissingBadHandleIndex());
        appendLine(dump, "slabInnerVmPresent", nativePlanningStats.slabInnerVmPresent());
        appendLine(dump, "slabInnerVmMissing", nativePlanningStats.slabInnerVmMissing());
        appendLine(dump, "slabInnerVmMissingExtracted", nativePlanningStats.slabInnerVmMissingExtracted());
        appendLine(dump, "slabInnerVmMissingUnsupportedNode", nativePlanningStats.slabInnerVmMissingUnsupportedNode());
        appendLine(dump, "slabInnerVmMissingInvalidProgram", nativePlanningStats.slabInnerVmMissingInvalidProgram());
        appendLine(dump, "slabInnerVmMissingIo", nativePlanningStats.slabInnerVmMissingIo());
        appendLine(dump, "axisYOnly", nativePlanningStats.axisYOnly());
        appendLine(dump, "axisXzOnly", nativePlanningStats.axisXzOnly());
        appendList(dump, "slabInnerUnsupportedClasses", nativePlanningStats.slabInnerUnsupportedClasses());

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

        appendSection(dump, "Registry Warmer");
        appendLine(dump, "calls", registryWarmerStats.calls());
        appendLine(dump, "skippedDuplicateCalls", registryWarmerStats.skippedDuplicateCalls());
        appendLine(dump, "skippedDuplicateEntries", registryWarmerStats.skippedDuplicateEntries());
        appendLine(dump, "warmedRouters", registryWarmerStats.warmedRouters());
        appendLine(dump, "warmedDensityFunctions", registryWarmerStats.warmedDensityFunctions());
        appendLine(dump, "failedEntries", registryWarmerStats.failedEntries());
        appendLine(dump, "budgetSkips", registryWarmerStats.budgetSkips());
        return dump.toString();
    }

    private static Path writeDumpToFile(String dump) throws IOException {
        Path debugDir = GeneratorAccelerator.getGameFolder().resolve("debug");
        Files.createDirectories(debugDir);
        Path dumpFile = debugDir.resolve("ga-debug-" + LocalDateTime.now().format(DUMP_TIMESTAMP) + ".txt");
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

    private static void appendList(StringBuilder dump, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        dump.append(key).append(":").append(System.lineSeparator());
        for (String value : values) {
            dump.append("- ").append(value).append(System.lineSeparator());
        }
    }
}
