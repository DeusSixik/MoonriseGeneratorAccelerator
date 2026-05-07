package dev.sixik.generator_accelerator.common.density.compiler;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.CommandDispatcher;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.common.density.compiler.natives.DfcNativeBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public final class DensityFunctionCompiler {
    public static final String MODID = "generator_accelerator";
    public static final Logger LOGGER = LogUtils.getLogger();

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

    public static void onServerStarted(MinecraftServer server) {
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
