package dev.sixik.generator_accelerator.common.density.compiler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.sixik.generator_accelerator.GARuntimeCaches;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
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
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class DensityFunctionCompiler {
    public static final Logger LOGGER = LoggerFactory.getLogger(DensityFunctionCompiler.class);
    private static final ExecutorService OPENCL_DIAGNOSTIC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DFC OpenCL diagnostics");
        thread.setDaemon(true);
        return thread;
    });

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
                        .then(Commands.literal("slabgridnoreadbench")
                                .executes(context -> runOpenClSlabGridNoReadBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridNoReadBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridNoReadBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridNoReadBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridcachedbench")
                                .executes(context -> runOpenClSlabGridCachedBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridCachedBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridCachedBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridCachedBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridcachednoreadbench")
                                .executes(context -> runOpenClSlabGridCachedNoReadBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridCachedNoReadBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridCachedNoReadBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridCachedNoReadBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridgenbench")
                                .executes(context -> runOpenClSlabGridGeneratedBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridGeneratedBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridGeneratedBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridGeneratedBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridgennoreadbench")
                                .executes(context -> runOpenClSlabGridGeneratedNoReadBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridGeneratedNoReadBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridGeneratedNoReadBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridGeneratedNoReadBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgriddirectbench")
                                .executes(context -> runOpenClSlabGridDirectBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridDirectBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridDirectBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridDirectBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgriddirectnoreadbench")
                                .executes(context -> runOpenClSlabGridDirectNoReadBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridDirectNoReadBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridDirectNoReadBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridDirectNoReadBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridnoisebench")
                                .executes(context -> runOpenClSlabGridNoiseBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridNoiseBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridNoiseBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridNoiseBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridnoisenoreadbench")
                                .executes(context -> runOpenClSlabGridNoiseNoReadBench(context.getSource(), 4, 8, 1024, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridNoiseNoReadBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridNoiseNoReadBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridNoiseNoReadBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridnoiseheavybench")
                                .executes(context -> runOpenClSlabGridNoiseHeavyBench(context.getSource(), 4, 8, 8192, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridNoiseHeavyBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridNoiseHeavyBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridNoiseHeavyBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridnoiseheavynoreadbench")
                                .executes(context -> runOpenClSlabGridNoiseHeavyNoReadBench(context.getSource(), 4, 8, 8192, 8, 2))
                                .then(Commands.argument("cellWidth", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("cellHeight", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1 << 20))
                                                        .executes(context -> runOpenClSlabGridNoiseHeavyNoReadBench(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                8,
                                                                2))
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                                                                .executes(context -> runOpenClSlabGridNoiseHeavyNoReadBench(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                        IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                        IntegerArgumentType.getInteger(context, "cells"),
                                                                        IntegerArgumentType.getInteger(context, "iterations"),
                                                                        2))
                                                                .then(Commands.argument("warmups", IntegerArgumentType.integer(0, 64))
                                                                        .executes(context -> runOpenClSlabGridNoiseHeavyNoReadBench(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cellWidth"),
                                                                                IntegerArgumentType.getInteger(context, "cellHeight"),
                                                                                IntegerArgumentType.getInteger(context, "cells"),
                                                                                IntegerArgumentType.getInteger(context, "iterations"),
                                                                                IntegerArgumentType.getInteger(context, "warmups")))))))))
                        .then(Commands.literal("slabgridrealnoisebench")
                                .executes(context -> runOpenClSlabGridRealNoiseBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisenoreadbench")
                                .executes(context -> runOpenClSlabGridRealNoiseNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseNoReadBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisecachedbench")
                                .executes(context -> runOpenClSlabGridRealNoiseCachedBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseCachedBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisecachednoreadbench")
                                .executes(context -> runOpenClSlabGridRealNoiseCachedNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseCachedNoReadBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisebyslotbench")
                                .executes(context -> runOpenClSlabGridRealNoiseBySlotBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseBySlotBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisebyslotnoreadbench")
                                .executes(context -> runOpenClSlabGridRealNoiseBySlotNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseBySlotNoReadBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisedirectbench")
                                .executes(context -> runOpenClSlabGridRealNoiseDirectBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4, 2))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseDirectBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots"), 2))
                                        .then(Commands.argument("usedSlots", IntegerArgumentType.integer(1, 16))
                                                .executes(context -> runOpenClSlabGridRealNoiseDirectBench(
                                                        context.getSource(), 4, 8, 8192, 8, 2,
                                                        IntegerArgumentType.getInteger(context, "slots"),
                                                        IntegerArgumentType.getInteger(context, "usedSlots"))))))
                        .then(Commands.literal("slabgridrealnoisedirectnoreadbench")
                                .executes(context -> runOpenClSlabGridRealNoiseDirectNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4, 2))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseDirectNoReadBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots"), 2))
                                        .then(Commands.argument("usedSlots", IntegerArgumentType.integer(1, 16))
                                                .executes(context -> runOpenClSlabGridRealNoiseDirectNoReadBench(
                                                        context.getSource(), 4, 8, 8192, 8, 2,
                                                        IntegerArgumentType.getInteger(context, "slots"),
                                                        IntegerArgumentType.getInteger(context, "usedSlots"))))))
                        .then(Commands.literal("slabgridrealnoisesourcebench")
                                .executes(context -> runOpenClSlabGridRealNoiseSourceBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseSourceBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisesourcenoreadbench")
                                .executes(context -> runOpenClSlabGridRealNoiseSourceNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseSourceNoReadBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisesourcenowrapnoreadbench")
                                .executes(context -> runOpenClSlabGridRealNoiseSourceNoWrapNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseSourceNoWrapNoReadBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
                        .then(Commands.literal("slabgridrealnoisesourceautonoreadbench")
                                .executes(context -> runOpenClSlabGridRealNoiseSourceAutoNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, 4))
                                .then(Commands.argument("slots", IntegerArgumentType.integer(2, 16))
                                        .executes(context -> runOpenClSlabGridRealNoiseSourceAutoNoReadBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "slots")))))
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
        return runOpenClDiagnostic(source, "slab VM coord selftest", () -> {
            DfcOpenClRuntime.SlabVmSelfTest result = DfcOpenClRuntime.slabVmCoordsSelfTest(repeats);
            return Component.literal(
                    "DFC OpenCL slab VM coord selftest: passed=" + result.passed()
                            + ", elapsedMs=" + formatNanosMillis(result.elapsedNanos())
                            + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabCoordBench(CommandSourceStack source, int repeats, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM coord bench", () -> {
            DfcOpenClRuntime.SlabVmCoordBenchmark result =
                    DfcOpenClRuntime.slabVmCoordsBenchmark(repeats, iterations, warmups);
            return Component.literal(
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabCellBench(CommandSourceStack source, int cellWidth, int cellHeight, int cells,
                                             int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM cell bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridBench(CommandSourceStack source, int cellWidth, int cellHeight, int cells,
                                             int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridNoReadBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                   int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridNoReadBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid no-read bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridCachedBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                   int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid cached bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridCachedBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid cached bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridCachedNoReadBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                         int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid cached no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridCachedNoReadBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid cached no-read bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridGeneratedBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                      int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid generated bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridGeneratedBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid generated bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridGeneratedNoReadBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                            int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid generated no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridGeneratedNoReadBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid generated no-read bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridDirectBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                   int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid direct bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridDirectBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid direct bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridDirectNoReadBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                         int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid direct no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridDirectNoReadBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid direct no-read bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridNoiseBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                   int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid noise bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridNoiseBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid noise bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridNoiseNoReadBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                         int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid noise no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridNoiseNoReadBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid noise no-read bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridNoiseHeavyBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                        int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid noise heavy bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridNoiseHeavyBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid noise heavy bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridNoiseHeavyNoReadBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                              int cells, int iterations, int warmups) {
        return runOpenClDiagnostic(source, "slab VM grid noise heavy no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridNoiseHeavyNoReadBenchmark(cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid noise heavy no-read bench: passed=" + result.passed()
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                       int cells, int iterations, int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal("DFC OpenCL real-noise bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseNoReadBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                             int cells, int iterations, int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal("DFC OpenCL real-noise no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseNoReadBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise no-read bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseCachedBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                             int cells, int iterations, int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal("DFC OpenCL real-noise cached bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise cached bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseCachedBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise cached bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseCachedNoReadBench(CommandSourceStack source, int cellWidth,
                                                                   int cellHeight, int cells, int iterations,
                                                                   int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL real-noise cached no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise cached no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseCachedNoReadBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise cached no-read bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseBySlotBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                             int cells, int iterations, int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal("DFC OpenCL real-noise by-slot bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise by-slot bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseBySlotBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise by-slot bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseBySlotNoReadBench(CommandSourceStack source, int cellWidth,
                                                                   int cellHeight, int cells, int iterations,
                                                                   int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL real-noise by-slot no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise by-slot no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseBySlotNoReadBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise by-slot no-read bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseDirectBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                             int cells, int iterations, int warmups, int slots,
                                                             int usedSlots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal("DFC OpenCL real-noise direct bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise direct bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseDirectBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups, usedSlots);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise direct bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", requestedUsedSlots=" + usedSlots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseDirectNoReadBench(CommandSourceStack source, int cellWidth,
                                                                   int cellHeight, int cells, int iterations,
                                                                   int warmups, int slots, int usedSlots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL real-noise direct no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise direct no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseDirectNoReadBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups, usedSlots);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise direct no-read bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", requestedUsedSlots=" + usedSlots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseSourceBench(CommandSourceStack source, int cellWidth, int cellHeight,
                                                             int cells, int iterations, int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal("DFC OpenCL real-noise source bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise source bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseSourceBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise source bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseSourceNoReadBench(CommandSourceStack source, int cellWidth,
                                                                   int cellHeight, int cells, int iterations,
                                                                   int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL real-noise source no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise source no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseSourceNoReadBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise source no-read bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseSourceNoWrapNoReadBench(CommandSourceStack source, int cellWidth,
                                                                         int cellHeight, int cells, int iterations,
                                                                         int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL real-noise source no-wrap no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise source no-wrap no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseSourceNoWrapNoReadBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise source no-wrap no-read bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClSlabGridRealNoiseSourceAutoNoReadBench(CommandSourceStack source, int cellWidth,
                                                                       int cellHeight, int cells, int iterations,
                                                                       int warmups, int slots) {
        NoiseSpec[] specs;
        try {
            specs = collectOpenClRealNoiseSpecs(source, slots);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL real-noise source auto no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid real-noise source auto no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridRealNoiseSourceAutoNoReadBenchmark(
                            specs, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid real-noise source auto no-read bench: passed=" + result.passed()
                            + ", requestedSlots=" + slots
                            + ", actualSlots=" + specs.length
                            + ", activeOctaves=" + countActiveOctaves(specs)
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
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClDiagnostic(CommandSourceStack source, String label, Supplier<Component> action) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("DFC OpenCL " + label + ": queued."), false);
        CompletableFuture.supplyAsync(action, OPENCL_DIAGNOSTIC_EXECUTOR)
                .whenComplete((component, throwable) -> server.execute(() -> {
                    if (throwable != null) {
                        source.sendFailure(Component.literal(
                                "DFC OpenCL " + label + ": failed: " + formatThrowable(throwable)));
                    } else {
                        source.sendSuccess(() -> component, false);
                    }
                }));
        return 1;
    }

    private static NoiseSpec[] collectOpenClRealNoiseSpecs(CommandSourceStack source, int requestedSlots) {
        int target = Math.max(2, requestedSlots);
        NoiseRouter router = source.getLevel().getChunkSource().randomState().router();
        DensityFunction[] fields = new DensityFunction[]{
                router.barrierNoise(),
                router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(),
                router.lavaNoise(),
                router.temperature(),
                router.vegetation(),
                router.continents(),
                router.erosion(),
                router.depth(),
                router.ridges(),
                router.initialDensityWithoutJaggedness(),
                router.finalDensity(),
                router.veinToggle(),
                router.veinRidged(),
                router.veinGap()
        };
        IdentityHashMap<NormalNoise, Boolean> seen = new IdentityHashMap<>();
        List<NoiseSpec> specs = new ArrayList<>(target);
        for (DensityFunction field : fields) {
            if (!(field instanceof CompiledDensityFunction compiled)) {
                continue;
            }
            for (NormalNoise noise : compiled.dfc$normalNoisesForDiagnostics()) {
                if (noise == null || seen.put(noise, Boolean.TRUE) != null) {
                    continue;
                }
                NoiseSpec spec = NoiseSpecCache.specFor(noise);
                if (spec == null || spec.totalActiveOctaves() <= 0) {
                    continue;
                }
                specs.add(spec);
                if (specs.size() >= target) {
                    return specs.toArray(new NoiseSpec[0]);
                }
            }
        }
        if (specs.size() < 2) {
            throw new IllegalStateException("found " + specs.size()
                    + " real NormalNoise specs in compiled router; enter a world with DFC compiled router first");
        }
        return specs.toArray(new NoiseSpec[0]);
    }

    private static int countActiveOctaves(NoiseSpec[] specs) {
        int total = 0;
        if (specs != null) {
            for (NoiseSpec spec : specs) {
                if (spec != null) {
                    total += spec.totalActiveOctaves();
                }
            }
        }
        return total;
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
                        + ", directStaging=" + DfcOpenClConfig.directStagingEnabled()
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

    private static String formatThrowable(Throwable throwable) {
        Throwable root = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getName();
        }
        return root.getClass().getSimpleName() + ": " + message;
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
