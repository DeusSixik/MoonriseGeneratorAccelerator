package dev.sixik.generator_accelerator.common.density.compiler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.sixik.generator_accelerator.GARuntimeCaches;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.common.density.compiler.natives.DfcNativeBridge;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClConfig;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClRuntime;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Locale;

public final class DensityFunctionCompiler {
    public static final Logger LOGGER = LoggerFactory.getLogger(DensityFunctionCompiler.class);

    private static volatile boolean initialized;

    private DensityFunctionCompiler() {}

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.info("DensityFunctionCompiler initialising - runtime DF JIT pipeline enabling.");
        DfcVectorSupport.logStatusOnce();
        LOGGER.info("DFC native noise: libraryLoaded={}, avx2={}",
                DfcNativeBridge.isAvailable(), DfcNativeBridge.hasAvx2());
        if (!DfcNativeBridge.isAvailable()) {
            Throwable err = DfcNativeBridge.nativeLoadError();
            if (err != null) {
                LOGGER.warn("DFC native noise: not loaded ({})", err.getMessage());
            } else {
                LOGGER.warn("DFC native noise: not loaded (unknown reason). Put natives/dfc/prebuilts/<platform>/... "
                        + "or set env DFC_NATIVE_LIBRARY to the absolute path of dfc_native.dll / .so / .dylib.");
            }
        }
        DfcOpenClRuntime.init();
    }

    public static void onServerStarting(MinecraftServer server) {
        GARuntimeCaches.resetForServerLifecycle();
    }

    public static void onServerStarted(MinecraftServer server) {
        RegistryWarmer.warmAll(server);
    }

    public static void onDatapackReload(MinecraftServer server) {
        GARuntimeCaches.resetForServerLifecycle();
        RegistryWarmer.warmAll(server);
    }

    public static void onServerStopped(MinecraftServer server) {
        GARuntimeCaches.resetForServerLifecycle();
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dfc")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dump")
                        .executes(context -> {
                            Compiler.DumpResult result = Compiler.dumpCompiledClasses();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Dumped " + result.classesDumped() + " compiled DFC classes to "
                                            + result.directory()
                                            + (result.failed() == 0 ? "" : " (" + result.failed() + " failed)")),
                                    false);
                            return result.classesDumped();
                        }))
                .then(Commands.literal("splinestats")
                        .executes(context -> {
                            DfcSplineStats.Stats stats = DfcSplineStats.snapshot();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "DFC spline runtime stats: enabled=" + stats.enabled()
                                            + ", calls=" + stats.calls()
                                            + ", linearCalls=" + stats.linearCalls()
                                            + ", binaryCalls=" + stats.binaryCalls()
                                            + ", lutCalls=" + stats.lutCalls()
                                            + ", interior=" + stats.interiorCalls()
                                            + ", leftExt=" + stats.leftExtrapolationCalls()
                                            + ", rightExt=" + stats.rightExtrapolationCalls()
                                            + ", totalMs=" + formatNanosMillis(stats.totalNanos())
                                            + ", avgNs=" + formatAverageNanos(stats.totalNanos(), stats.calls())
                                            + ", linearAvgNs=" + formatAverageNanos(stats.linearNanos(), stats.linearCalls())
                                            + ", binaryAvgNs=" + formatAverageNanos(stats.binaryNanos(), stats.binaryCalls())
                                            + ", lutAvgNs=" + formatAverageNanos(stats.lutNanos(), stats.lutCalls())),
                                    false);
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "DFC spline runtime buckets: "
                                            + "<=2=" + formatBucket(stats.bucketLe2())
                                            + ", 3..4=" + formatBucket(stats.bucket3To4())
                                            + ", 5..8=" + formatBucket(stats.bucket5To8())
                                            + ", >=9=" + formatBucket(stats.bucketGe9())),
                                    false);
                            return (int) Math.min(Integer.MAX_VALUE, stats.calls());
                        })
                        .then(Commands.literal("top")
                                .executes(context -> {
                                    var top = DfcSplineStats.snapshotTopClasses(5);
                                    if (top.isEmpty()) {
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("DFC spline runtime top: no samples yet."),
                                                false);
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "DFC spline runtime top: " + top.stream()
                                                    .map(DensityFunctionCompiler::formatSplineTopEntry)
                                                    .reduce((a, b) -> a + ", " + b)
                                                    .orElse("")),
                                            false);
                                    return top.size();
                                }))
                        .then(Commands.literal("reset")
                                .executes(context -> {
                                    DfcSplineStats.reset();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("DFC spline runtime stats reset."),
                                            false);
                                    return 1;
                                })))
                .then(Commands.literal("cellfillparity")
                        .executes(context -> {
                            DfcCellFillParity.Stats stats = DfcCellFillParity.snapshotStats();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "DFC cell-fill parity: enabled=" + stats.enabled()
                                            + ", candidates=" + stats.candidates()
                                            + ", fastEligible=" + stats.fastEligible()
                                            + ", lazyFastEligible=" + stats.lazyFastEligible()
                                            + ", fallbacks=" + stats.fallbacks()
                                            + ", checks=" + stats.checks()
                                            + ", passes=" + stats.passes()
                                            + ", failures=" + stats.failures()
                                            + ", skipped=" + stats.skipped()
                                            + ", remaining=" + stats.remaining() + "/" + stats.maxChecks()
                                            + ", epsilon=" + stats.epsilon()),
                                    false);
                            if (!stats.fallbackClasses().isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "DFC cell-fill fallback classes: " + String.join(", ", stats.fallbackClasses())),
                                        false);
                            }
                            return (int) stats.failures();
                        }))
                .then(Commands.literal("opencl")
                        .executes(context -> {
                            DfcOpenClRuntime.Status status = DfcOpenClRuntime.status();
                            sendOpenClStatus(context.getSource(), status);
                            return status.devices().size();
                        })
                        .then(Commands.literal("probe")
                                .executes(context -> {
                                    DfcOpenClRuntime.Status status = DfcOpenClRuntime.probe(true);
                                    sendOpenClStatus(context.getSource(), status);
                                    return status.available() ? status.devices().size() : 0;
                                }))
                        .then(Commands.literal("slabtest")
                                .executes(context -> {
                                    DfcOpenClRuntime.SlabVmSelfTest result = DfcOpenClRuntime.slabVmSelfTest();
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "DFC OpenCL slab VM selftest: passed=" + result.passed()
                                                    + ", elapsedMs=" + formatNanosMillis(result.elapsedNanos())
                                                    + ", device="
                                                    + (result.device() == null ? "none" : result.device().shortDescription())
                                                    + ", message=" + result.message()),
                                            false);
                                    return result.passed() ? 1 : 0;
                                }))
                        .then(Commands.literal("slabcoordtest")
                                .executes(context -> runOpenClSlabCoordTest(context.getSource(), 1024))
                                .then(Commands.argument("repeats", IntegerArgumentType.integer(1, 1 << 20))
                                        .executes(context -> runOpenClSlabCoordTest(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "repeats")))))
                        .then(Commands.literal("slabcoordbench")
                                .executes(context -> runOpenClSlabCoordBench(context.getSource(), 8192, 8, 2))
                                .then(Commands.argument("repeats", IntegerArgumentType.integer(1, 1 << 20))
                                        .executes(context -> runOpenClSlabCoordBench(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "repeats"),
                                                8,
                                                2))
                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                .executes(context -> runOpenClSlabCoordBench(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "repeats"),
                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                        2))
                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                        .executes(context -> runOpenClSlabCoordBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "repeats"),
                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                IntegerArgumentType.getInteger(context, "warmups")))))))
                        .then(Commands.literal("slabcellbench")
                                .executes(context -> runOpenClSlabCellBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabCellBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabCellBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabCellBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridbench")
                                .executes(context -> runOpenClSlabGridBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("stats")
                                .executes(context -> {
                                    sendOpenClStats(context.getSource());
                                    return (int) Math.min(Integer.MAX_VALUE,
                                            DfcOpenClStats.snapshot().slabSucceeded());
                                })
                                .then(Commands.literal("reset")
                                        .executes(context -> {
                                            DfcOpenClStats.reset();
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("DFC OpenCL stats reset."),
                                                    false);
                                            return 1;
                                        }))))
                .then(Commands.literal("cellfillstats")
                        .executes(context -> {
                            DfcCellFillStats.Stats stats = DfcCellFillStats.snapshot();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "DFC cell-fill stats: enabled=" + stats.enabled()
                                            + ", cellScalar=" + stats.cellScalar()
                                            + ", cellCompiled=" + stats.cellCompiled()
                                            + ", cellNativeSlabInner=" + stats.cellNativeSlabInner()
                                            + ", cellUnknown=" + stats.cellUnknown()
                                            + ", cellXzSlab=" + stats.cellXzSlab()
                                            + ", columnsScalar=" + stats.columnsScalar()
                                            + ", cellExternAccumulate=" + stats.cellExternAccumulate()
                                            + ", cellExternScalarResidual=" + stats.cellExternScalarResidual()
                                            + ", columnsJavaBatched=" + stats.columnsJavaBatched()
                                            + ", columnsNativeInner=" + stats.columnsNativeInner()),
                                    false);
                            if (!stats.fastFillerClasses().isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "DFC cell-fill fast classes: " + stats.fastFillerClasses().stream()
                                                .map(s -> s.className() + "=" + s.calls()
                                                        + "/" + s.nativeSlabInnerCalls())
                                                .reduce((a, b) -> a + ", " + b)
                                                .orElse("")),
                                        false);
                            }
                            if (!stats.fastFillerDebugClasses().isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "DFC cell-fill fast debug: " + stats.fastFillerDebugClasses().stream()
                                                .map(s -> s.className()
                                                        + "{src=" + s.sourceRootClass()
                                                        + ", lattice=" + s.latticeEmitted()
                                                        + ", slabProgram=" + s.slabInnerProgramPresent()
                                                        + ", cellAddLattice=" + s.cellAddLatticeSpecialized()
                                                        + ", cellAddExtern=" + s.cellAddExternSpecialized()
                                                        + ", root=" + s.rootDebug()
                                                        + "}")
                                                .reduce((a, b) -> a + ", " + b)
                                                .orElse("")),
                                        false);
                            }
                            if (!stats.sourceFillerClasses().isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "DFC cell-fill source classes: "
                                                + String.join(", ", stats.sourceFillerClasses())),
                                        false);
                            }
                            if (!stats.residualExternFallbackClasses().isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "DFC cell-fill residual extern fallback classes: "
                                                + String.join(", ", stats.residualExternFallbackClasses())),
                                        false);
                            }
                            DfcNativePlanningStats.Stats nativeStats = DfcNativePlanningStats.snapshot();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "DFC native planning stats: latticeRoots=" + nativeStats.latticeRoots()
                                            + ", nativeOpsDisabled=" + nativeStats.nativeOpsDisabled()
                                            + ", slabPlanPresent=" + nativeStats.slabPlanPresent()
                                            + ", slabPlanMissing=" + nativeStats.slabPlanMissing()
                                            + ", slabPlanMissingNoSlots=" + nativeStats.slabPlanMissingNoSlots()
                                            + ", slabPlanMissingUnsafeCoords=" + nativeStats.slabPlanMissingUnsafeCoords()
                                            + ", slabPlanMissingBadHandleIndex=" + nativeStats.slabPlanMissingBadHandleIndex()
                                            + ", slabInnerVmPresent=" + nativeStats.slabInnerVmPresent()
                                            + ", slabInnerVmMissing=" + nativeStats.slabInnerVmMissing()
                                            + ", slabInnerMissingExtracted=" + nativeStats.slabInnerVmMissingExtracted()
                                            + ", slabInnerMissingUnsupportedNode=" + nativeStats.slabInnerVmMissingUnsupportedNode()
                                            + ", slabInnerMissingInvalidProgram=" + nativeStats.slabInnerVmMissingInvalidProgram()
                                            + ", slabInnerMissingIo=" + nativeStats.slabInnerVmMissingIo()
                                            + ", axisYOnly=" + nativeStats.axisYOnly()
                                            + ", axisXzOnly=" + nativeStats.axisXzOnly()),
                                    false);
                            if (!nativeStats.slabInnerUnsupportedClasses().isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "DFC slab-inner unsupported classes: "
                                                + String.join(", ", nativeStats.slabInnerUnsupportedClasses())),
                                        false);
                            }
                            return 1;
                        })));
    }

    private static int runOpenClSlabCoordTest(CommandSourceStack source, int repeats) {
        DfcOpenClRuntime.SlabVmSelfTest result = DfcOpenClRuntime.slabVmCoordsSelfTest(repeats);
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL slab VM coord selftest: passed=" + result.passed()
                        + ", elapsedMs=" + formatNanosMillis(result.elapsedNanos())
                        + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                        + ", message=" + result.message()),
                false);
        return result.passed() ? 1 : 0;
    }

    private static int runOpenClSlabCoordBench(CommandSourceStack source, int repeats, int iterations, int warmups) {
        DfcOpenClRuntime.SlabVmCoordBenchmark result =
                DfcOpenClRuntime.slabVmCoordsBenchmark(repeats, iterations, warmups);
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL slab VM coord bench: passed=" + result.passed()
                        + ", repeats=" + result.repeats()
                        + ", iterations=" + result.iterations()
                        + ", warmups=" + result.warmups()
                        + ", elementsPerIter=" + result.elementsPerIteration()
                        + ", totalElements=" + result.totalElements()
                        + ", avgMs=" + formatNanosMillis(result.averageNanos())
                        + ", bestMs=" + formatNanosMillis(result.bestNanos())
                        + ", worstMs=" + formatNanosMillis(result.worstNanos())
                        + ", avgElemNs=" + formatAverageNanos(result.totalNanos(), result.totalElements())
                        + ", bestElemNs=" + formatAverageNanos(result.bestNanos(), result.elementsPerIteration())
                        + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                        + ", message=" + result.message()),
                false);
        return result.passed() ? 1 : 0;
    }

    private static int runOpenClSlabCellBench(CommandSourceStack source, int cellWidth, int cellHeight, int cells,
                                             int iterations, int warmups) {
        DfcOpenClRuntime.SlabVmCellBenchmark result =
                DfcOpenClRuntime.slabVmCellBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL slab VM cell bench: passed=" + result.passed()
                        + ", cellWidth=" + result.cellWidth()
                        + ", cellHeight=" + result.cellHeight()
                        + ", cells=" + result.cells()
                        + ", iterations=" + result.iterations()
                        + ", warmups=" + result.warmups()
                        + ", elementsPerIter=" + result.elementsPerIteration()
                        + ", totalElements=" + result.totalElements()
                        + ", avgMs=" + formatNanosMillis(result.averageNanos())
                        + ", bestMs=" + formatNanosMillis(result.bestNanos())
                        + ", worstMs=" + formatNanosMillis(result.worstNanos())
                        + ", avgElemNs=" + formatAverageNanos(result.totalNanos(), result.totalElements())
                        + ", bestElemNs=" + formatAverageNanos(result.bestNanos(), result.elementsPerIteration())
                        + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                        + ", message=" + result.message()),
                false);
        return result.passed() ? 1 : 0;
    }

    private static int runOpenClSlabGridBench(CommandSourceStack source, int cellWidth, int cellHeight, int cells,
                                             int iterations, int warmups) {
        DfcOpenClRuntime.SlabVmCellBenchmark result =
                DfcOpenClRuntime.slabVmCellGridBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL slab VM grid bench: passed=" + result.passed()
                        + ", cellWidth=" + result.cellWidth()
                        + ", cellHeight=" + result.cellHeight()
                        + ", cells=" + result.cells()
                        + ", iterations=" + result.iterations()
                        + ", warmups=" + result.warmups()
                        + ", elementsPerIter=" + result.elementsPerIteration()
                        + ", totalElements=" + result.totalElements()
                        + ", avgMs=" + formatNanosMillis(result.averageNanos())
                        + ", bestMs=" + formatNanosMillis(result.bestNanos())
                        + ", worstMs=" + formatNanosMillis(result.worstNanos())
                        + ", avgElemNs=" + formatAverageNanos(result.totalNanos(), result.totalElements())
                        + ", bestElemNs=" + formatAverageNanos(result.bestNanos(), result.elementsPerIteration())
                        + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                        + ", message=" + result.message()),
                false);
        return result.passed() ? 1 : 0;
    }

    private static void sendOpenClStatus(CommandSourceStack source, DfcOpenClRuntime.Status status) {
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL: enabled=" + status.enabled()
                        + ", probed=" + status.probed()
                        + ", available=" + status.available()
                        + ", devices=" + status.devices().size()
                        + ", runtimeTested=" + status.runtimeTested()
                        + (status.runtimeTested() ? ", runtimePassed=" + status.runtimePassed() : "")
                        + ", slabDispatchConfigured=" + DfcOpenClRuntime.slabVmDispatchConfigured()
                        + ", slabDispatchAvailable=" + DfcOpenClRuntime.slabVmDispatchAvailable()
                        + ", slabDispatchBroken=" + DfcOpenClRuntime.slabVmDispatchBroken()
                        + ", slabDispatchRequested=" + DfcOpenClConfig.slabVmDispatchEnabled()
                        + ", worldgenBridge=" + DfcOpenClConfig.worldgenBridgeEnabled()
                        + ", slabMinElements=" + DfcOpenClConfig.slabVmMinElements()
                        + ", bridgeMaxElements=" + DfcOpenClConfig.currentBridgeMaxElements()
                        + ", coordBenchMaxElements=" + DfcOpenClConfig.coordBenchMaxElements()
                        + (status.error() == null ? "" : ", error=" + status.error())),
                false);
        if (!status.enabled()) {
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL: enable config enableDensityCompilerOpenCL or -Ddfc.opencl.enabled=true to probe."),
                    false);
            return;
        }
        if (!status.probed()) {
            source.sendSuccess(() -> Component.literal("DFC OpenCL: run /dfc opencl probe to enumerate devices."),
                    false);
            return;
        }
        int limit = Math.min(status.devices().size(), DfcOpenClConfig.maxLoggedDevices());
        for (int i = 0; i < limit; i++) {
            int deviceIndex = i;
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL device[" + deviceIndex + "]: "
                            + status.devices().get(deviceIndex).shortDescription()),
                    false);
        }
        if (status.selectedDevice() != null) {
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL selected runtime device: " + status.selectedDevice().shortDescription()),
                    false);
        }
        if (status.devices().size() > limit) {
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL: " + (status.devices().size() - limit)
                            + " more device(s) hidden by dfc.opencl.maxLoggedDevices."),
                    false);
        }
    }

    private static void sendOpenClStats(CommandSourceStack source) {
        DfcOpenClStats.Snapshot stats = DfcOpenClStats.snapshot();
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL slab VM stats: attempts=" + stats.slabAttempts()
                        + ", submitted=" + stats.slabSubmitted()
                        + ", succeeded=" + stats.slabSucceeded()
                        + ", failed=" + stats.slabFailed()
                        + ", elements=" + stats.slabElements()
                        + ", totalMs=" + formatNanosMillis(stats.slabNanos())
                        + ", avgNs=" + formatAverageNanos(stats.slabNanos(), stats.slabSucceeded())
                        + ", avgElemNs=" + formatAverageNanos(stats.slabNanos(), stats.slabElements())
                        + ", maxMs=" + formatNanosMillis(stats.slabMaxNanos())
                        + ", minElements=" + stats.slabMinElements()),
                false);
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL slab VM fallback: disabled=" + stats.slabSkippedDisabled()
                        + ", unavailable=" + stats.slabSkippedUnavailable()
                        + ", broken=" + stats.slabSkippedBroken()
                        + ", belowMin=" + stats.slabSkippedBelowMin()
                        + ", jni=" + stats.slabFallbackJni()
                        + ", java=" + stats.slabFallbackJava()),
                false);
    }

    private static String formatBucket(DfcSplineStats.BucketStats bucket) {
        return bucket.calls() + "/" + formatNanosMillis(bucket.nanos()) + "ms";
    }

    private static String formatNanosMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0d);
    }

    private static String formatAverageNanos(long nanos, long calls) {
        if (calls <= 0L) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.1f", nanos / (double) calls);
    }

    private static String formatSplineTopEntry(DfcSplineStats.ClassStats stats) {
        return stats.className()
                + "{ms=" + formatNanosMillis(stats.totalNanos())
                + ", calls=" + stats.calls()
                + ", avgNs=" + formatAverageNanos(stats.totalNanos(), stats.calls())
                + ", linear=" + stats.linearCalls()
                + ", binary=" + stats.binaryCalls()
                + ", lut=" + stats.lutCalls()
                + ", leftExt=" + stats.leftExtrapolationCalls()
                + ", rightExt=" + stats.rightExtrapolationCalls()
                + ", 5..8=" + stats.bucket5To8().calls()
                + "/" + formatNanosMillis(stats.bucket5To8().nanos()) + "ms"
                + ", >=9=" + stats.bucketGe9().calls()
                + "/" + formatNanosMillis(stats.bucketGe9().nanos()) + "ms"
                + ", src=" + stats.sourceRootClass()
                + ", root=" + stats.rootDebug()
                + "}";
    }

    public static boolean isModLoaded(String modId) {
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            return (boolean) fabricLoader.getMethod("isModLoaded", String.class).invoke(instance, modId);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            Method isLoaded = modList.getMethod("isLoaded", String.class);
            return (boolean) isLoaded.invoke(instance, modId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
