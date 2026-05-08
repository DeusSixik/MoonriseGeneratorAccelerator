package dev.sixik.generator_accelerator.common.density.compiler;

import com.mojang.brigadier.CommandDispatcher;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.common.density.compiler.natives.DfcNativeBridge;
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
    }

    public static void onServerStarting(MinecraftServer server) {
        RegistryWarmer.warmAll(server);
    }

    public static void onDatapackReload(MinecraftServer server) {
        RegistryWarmer.warmAll(server);
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
