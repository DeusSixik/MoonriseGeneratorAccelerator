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
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.common.density.compiler.natives.DfcNativeBridge;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClConfig;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClCompiledPlanRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClRuntime;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int OPENCL_COMPILED_PLAN_MARKER_EXPAND_DEPTH = 8;
    private static final int OPENCL_SOURCE_BENCH_MAX_SLOTS = 16;
    private static final int OPENCL_SOURCE_BENCH_MAX_OCTAVES = 32;
    private static final int OPENCL_SOURCE_BENCH_MAX_COMPUTED = 8;
    private static final int OPENCL_FINAL_CHUNK_MAX_SLOTS = 8;
    private static final int OPENCL_FINAL_CHUNK_MAX_OCTAVES = 16;
    private static final int OPENCL_FINAL_CHUNK_MAX_COMPUTED = 2;

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
                        .then(Commands.literal("slabgridcompiledsourceautonoreadbench")
                                .executes(context -> runOpenClSlabGridCompiledSourceAutoNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2)))
                        .then(Commands.literal("slabgridcompiledsourcefinaldensitybench")
                                .executes(context -> runOpenClSlabGridCompiledSourceFinalDensityNoReadBench(
                                        context.getSource(), 4, 8, 8192, 8, 2)))
                        .then(Commands.literal("compiledplancandidates")
                                .executes(context -> sendOpenClCompiledPlanCandidates(context.getSource())))
                        .then(Commands.literal("compiledfinaldensitychunks")
                                .executes(context -> sendOpenClCompiledFinalDensityChunks(context.getSource())))
                        .then(Commands.literal("compiledfinaldensitychunkdeps")
                                .executes(context -> sendOpenClCompiledFinalDensityChunkDeps(context.getSource())))
                        .then(Commands.literal("compiledfinaldensitychunkwaves")
                                .executes(context -> sendOpenClCompiledFinalDensityChunkWaves(context.getSource())))
                        .then(Commands.literal("compiledfinaldensitywavesbench")
                                .executes(context -> runOpenClCompiledFinalDensityWavesBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, false, false, false)))
                        .then(Commands.literal("compiledfinaldensitywavescompactbench")
                                .executes(context -> runOpenClCompiledFinalDensityWavesBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, true, false, false)))
                        .then(Commands.literal("compiledfinaldensitywavefusedbench")
                                .executes(context -> runOpenClCompiledFinalDensityWavesBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, true, true, false)))
                        .then(Commands.literal("compiledfinaldensitywavefusedcheck")
                                .executes(context -> runOpenClCompiledFinalDensityWaveFusedCheck(
                                        context.getSource(), 4, 8, 128, false)))
                        .then(Commands.literal("compiledfinaldensityhybridcheck")
                                .executes(context -> runOpenClCompiledFinalDensityWaveFusedCheck(
                                        context.getSource(), 4, 8, 128, true)))
                        .then(Commands.literal("compiledfinaldensityallwavesfusedbench")
                                .executes(context -> runOpenClCompiledFinalDensityWavesBench(
                                        context.getSource(), 4, 8, 8192, 8, 2, true, true, true)))
                        .then(Commands.literal("compiledfinaldensitychunkcompile")
                                .then(Commands.argument("chunk", IntegerArgumentType.integer(0, 255))
                                        .executes(context -> runOpenClCompiledFinalDensityChunkCompileProbe(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "chunk")))))
                        .then(Commands.literal("compiledfinaldensitychunkbench")
                                .then(Commands.argument("chunk", IntegerArgumentType.integer(0, 255))
                                        .executes(context -> runOpenClCompiledFinalDensityChunkBench(
                                                context.getSource(), 4, 8, 8192, 8, 2,
                                                IntegerArgumentType.getInteger(context, "chunk")))))
                        .then(Commands.literal("compiledplanexterns")
                                .executes(context -> sendOpenClCompiledPlanExterns(context.getSource())))
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

    private static int runOpenClSlabGridCompiledSourceAutoNoReadBench(CommandSourceStack source, int cellWidth,
                                                                      int cellHeight, int cells, int iterations,
                                                                      int warmups) {
        DfcOpenClRuntime.OpenClCompiledPlan plan;
        try {
            plan = collectOpenClCompiledRouterSourceBenchPlan(source);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL compiled source auto no-read bench: " + formatThrowable(throwable)));
            return 0;
        }
        return runOpenClDiagnostic(source, "slab VM grid compiled source auto no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridCompiledPlanSourceAutoNoReadBenchmark(
                            plan, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid compiled source auto no-read bench: passed=" + result.passed()
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

    private static int runOpenClSlabGridCompiledSourceFinalDensityNoReadBench(CommandSourceStack source,
                                                                              int cellWidth, int cellHeight,
                                                                              int cells, int iterations,
                                                                              int warmups) {
        DfcOpenClRuntime.OpenClCompiledPlan plan;
        try {
            plan = collectOpenClCompiledFinalDensityPlan(source);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL compiled finalDensity source no-read bench: " + formatThrowable(throwable)));
            return 0;
        }

        String rejection = openClCompiledPlanSourceBenchRejection(plan);
        if (rejection != null) {
            DfcOpenClRuntime.OpenClCompiledPlan blockedPlan = plan;
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL slab VM grid compiled finalDensity source no-read bench: blocked=true, "
                            + describeOpenClCompiledPlanSourceLimits(blockedPlan)
                            + ", reason=" + rejection),
                    false);
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL slab VM grid compiled finalDensity source no-read bench: not queued; use "
                            + "/dfc opencl slabgridcompiledsourceautonoreadbench for the safe source bench."),
                    false);
            return 0;
        }

        return runOpenClDiagnostic(source, "slab VM grid compiled finalDensity source no-read bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.slabVmCellGridCompiledPlanSourceAutoNoReadBenchmark(
                            plan, cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL slab VM grid compiled finalDensity source no-read bench: passed=" + result.passed()
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

    private static DfcOpenClRuntime.OpenClCompiledPlan collectOpenClCompiledRouterPlan(CommandSourceStack source) {
        NoiseRouter router = source.getLevel().getChunkSource().randomState().router();
        RouterDensityCandidate[] candidates = openClRouterCandidates(router);

        List<String> failures = new ArrayList<>();
        DfcOpenClRuntime.OpenClCompiledPlan best = null;
        for (RouterDensityCandidate candidate : candidates) {
            DfcOpenClRuntime.OpenClCompiledPlan plan = tryCollectOpenClCompiledPlan(candidate, failures);
            if (plan != null) {
                best = betterOpenClCompiledPlan(best, plan);
            }
        }
        if (best != null) {
            return best;
        }
        DfcOpenClRuntime.OpenClCompiledPlan syntheticPlan = tryCollectSyntheticOpenClCompiledPlan(source, failures);
        if (syntheticPlan != null) {
            return syntheticPlan;
        }

        throw new IllegalStateException("no router density field has an OpenCL diagnostic plan: "
                + limitedFailureSummary(failures, 8));
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan collectOpenClCompiledRouterSourceBenchPlan(
            CommandSourceStack source) {
        NoiseRouter router = source.getLevel().getChunkSource().randomState().router();
        List<String> failures = new ArrayList<>();
        DfcOpenClRuntime.OpenClCompiledPlan best = null;
        for (RouterDensityCandidate candidate : openClRouterCandidates(router)) {
            DfcOpenClRuntime.OpenClCompiledPlan plan = tryCollectOpenClCompiledPlan(candidate, failures);
            if (plan != null && openClCompiledPlanSourceBenchSafe(plan)) {
                best = betterOpenClCompiledPlan(best, plan);
            }
        }
        if (best != null) {
            return best;
        }
        DfcOpenClRuntime.OpenClCompiledPlan syntheticPlan = tryCollectSyntheticOpenClCompiledPlan(source, failures);
        if (syntheticPlan != null) {
            return syntheticPlan;
        }
        throw new IllegalStateException("no source-bench-safe router density field has an OpenCL diagnostic plan: "
                + limitedFailureSummary(failures, 8));
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan collectOpenClCompiledFinalDensityPlan(
            CommandSourceStack source) {
        NoiseRouter router = source.getLevel().getChunkSource().randomState().router();
        List<String> failures = new ArrayList<>();
        DfcOpenClRuntime.OpenClCompiledPlan plan = tryCollectOpenClCompiledPlan(
                new RouterDensityCandidate("finalDensity", router.finalDensity()), failures);
        if (plan != null) {
            return plan;
        }
        throw new IllegalStateException(limitedFailureSummary(failures, 4));
    }

    private static int sendOpenClCompiledFinalDensityChunks(CommandSourceStack source) {
        try {
            DfcOpenClRuntime.OpenClCompiledPlan plan = collectOpenClCompiledFinalDensityPlan(source);
            List<OpenClCompiledPlanChunk> chunks = new ArrayList<>();
            List<Integer> blockedSlots = new ArrayList<>();
            collectOpenClCompiledPlanChunks(plan, chunks, blockedSlots);

            int slots = plan.specs() == null ? 0 : plan.specs().length;
            int chunkedSlots = 0;
            for (OpenClCompiledPlanChunk chunk : chunks) {
                chunkedSlots += chunk.count();
            }
            int finalChunkedSlots = chunkedSlots;
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL finalDensity chunks: " + describeOpenClCompiledPlanSourceLimits(plan)
                            + ", greedyChunks=" + chunks.size()
                            + ", chunkedSlots=" + finalChunkedSlots + "/" + slots
                            + ", blockedSlots=" + blockedSlots.size()
                            + ", caps=slots<=" + OPENCL_FINAL_CHUNK_MAX_SLOTS
                            + "/octaves<=" + OPENCL_FINAL_CHUNK_MAX_OCTAVES
                            + "/computed<=" + OPENCL_FINAL_CHUNK_MAX_COMPUTED
                            + "/external=0"),
                    false);

            int chunkLimit = Math.min(chunks.size(), 32);
            for (int i = 0; i < chunkLimit; i++) {
                int chunkIndex = i;
                OpenClCompiledPlanChunk chunk = chunks.get(i);
                boolean[] inputs = DfcOpenClRuntime.compiledPlanChunkExternalInputs(
                        plan, chunk.startSlot(), chunk.endSlot());
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity chunk[" + chunkIndex + "]: "
                                + describeOpenClCompiledPlanChunk(chunk)
                                + ", inputs=" + describeOpenClSlotSet(inputs, 8)),
                        false);
            }
            if (chunks.size() > chunkLimit) {
                int hiddenChunks = chunks.size() - chunkLimit;
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity chunks: +" + hiddenChunks + " more chunk(s) hidden."),
                        false);
            }

            int blockedLimit = Math.min(blockedSlots.size(), 16);
            for (int i = 0; i < blockedLimit; i++) {
                int blockedIndex = i;
                int slot = blockedSlots.get(i);
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity blocked[" + blockedIndex + "]: "
                                + describeOpenClCompiledPlanBlockedSlot(plan, slot)),
                        false);
            }
            if (blockedSlots.size() > blockedLimit) {
                int hiddenBlocked = blockedSlots.size() - blockedLimit;
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity blocked: +" + hiddenBlocked + " more slot(s) hidden."),
                        false);
            }
            return chunks.size();
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL finalDensity chunks: " + formatThrowable(throwable)));
            return 0;
        }
    }

    private static int sendOpenClCompiledFinalDensityChunkDeps(CommandSourceStack source) {
        try {
            DfcOpenClRuntime.OpenClCompiledPlan plan = collectOpenClCompiledFinalDensityPlan(source);
            List<OpenClCompiledPlanChunk> chunks = new ArrayList<>();
            List<Integer> blockedSlots = new ArrayList<>();
            collectOpenClCompiledPlanChunks(plan, chunks, blockedSlots);

            int slots = plan.specs() == null ? 0 : plan.specs().length;
            int[] slotOwners = buildOpenClChunkSlotOwners(chunks, slots);
            List<boolean[]> chunkInputs = new ArrayList<>(chunks.size());
            int readyChunks = 0;
            int waitingChunks = 0;
            int blockedInputRefs = 0;
            int producerEdges = 0;
            for (OpenClCompiledPlanChunk chunk : chunks) {
                boolean[] inputs = DfcOpenClRuntime.compiledPlanChunkExternalInputs(
                        plan, chunk.startSlot(), chunk.endSlot());
                chunkInputs.add(inputs);
                if (countTrue(inputs) == 0) {
                    readyChunks++;
                } else {
                    waitingChunks++;
                }
                blockedInputRefs += countOpenClBlockedInputs(inputs, slotOwners);
                producerEdges += countOpenClChunkProducerChunks(inputs, slotOwners);
            }

            int finalReadyChunks = readyChunks;
            int finalWaitingChunks = waitingChunks;
            int finalBlockedInputRefs = blockedInputRefs;
            int finalProducerEdges = producerEdges;
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL finalDensity chunk deps: chunks=" + chunks.size()
                            + ", ready=" + finalReadyChunks
                            + ", waiting=" + finalWaitingChunks
                            + ", producerEdges=" + finalProducerEdges
                            + ", blockedInputRefs=" + finalBlockedInputRefs
                            + ", blockedSlots=" + blockedSlots.size()),
                    false);

            int chunkLimit = Math.min(chunks.size(), 32);
            for (int i = 0; i < chunkLimit; i++) {
                int chunkIndex = i;
                OpenClCompiledPlanChunk chunk = chunks.get(i);
                boolean[] inputs = chunkInputs.get(i);
                int blockedInputs = countOpenClBlockedInputs(inputs, slotOwners);
                String state = countTrue(inputs) == 0 ? "ready" : blockedInputs == 0 ? "staged" : "blocked";
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity chunk deps[" + chunkIndex + "]: "
                                + describeOpenClCompiledPlanChunk(chunk)
                                + ", inputs=" + describeOpenClSlotSet(inputs, 8)
                                + ", producerChunks=" + describeOpenClChunkProducerSet(inputs, slotOwners, 8)
                                + ", blockedInputs=" + describeOpenClBlockedInputSet(inputs, slotOwners, 8)
                                + ", state=" + state),
                        false);
            }
            if (chunks.size() > chunkLimit) {
                int hiddenChunks = chunks.size() - chunkLimit;
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity chunk deps: +" + hiddenChunks + " more chunk(s) hidden."),
                        false);
            }
            return chunks.size();
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL finalDensity chunk deps: " + formatThrowable(throwable)));
            return 0;
        }
    }

    private static int sendOpenClCompiledFinalDensityChunkWaves(CommandSourceStack source) {
        try {
            DfcOpenClRuntime.OpenClCompiledPlan plan = collectOpenClCompiledFinalDensityPlan(source);
            List<OpenClCompiledPlanChunk> chunks = new ArrayList<>();
            List<Integer> blockedSlots = new ArrayList<>();
            collectOpenClCompiledPlanChunks(plan, chunks, blockedSlots);

            int slots = plan.specs() == null ? 0 : plan.specs().length;
            int[] slotOwners = buildOpenClChunkSlotOwners(chunks, slots);
            List<boolean[]> chunkInputs = new ArrayList<>(chunks.size());
            for (OpenClCompiledPlanChunk chunk : chunks) {
                chunkInputs.add(DfcOpenClRuntime.compiledPlanChunkExternalInputs(
                        plan, chunk.startSlot(), chunk.endSlot()));
            }

            OpenClChunkWavePlan wavePlan = collectOpenClChunkWaves(chunkInputs, slotOwners);
            boolean[] blockedInputUnion = collectOpenClBlockedInputUnion(chunkInputs, slotOwners);
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL finalDensity chunk waves: chunks=" + chunks.size()
                            + ", waves=" + wavePlan.waves().size()
                            + ", scheduled=" + countTrue(wavePlan.scheduledChunks())
                            + ", directBlocked=" + describeOpenClSlotSet(wavePlan.directBlockedChunks(), 12)
                            + ", stalled=" + describeOpenClSlotSet(wavePlan.stalledChunks(), 12)
                            + ", blockedInputs=" + describeOpenClSlotSet(blockedInputUnion, 12)
                            + ", blockedSlots=" + blockedSlots.size()),
                    false);

            int waveLimit = Math.min(wavePlan.waves().size(), 16);
            for (int i = 0; i < waveLimit; i++) {
                int waveIndex = i;
                boolean[] wave = wavePlan.waves().get(i);
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity chunk wave[" + waveIndex + "]: chunks="
                                + describeOpenClSlotSet(wave, 16)),
                        false);
            }
            if (wavePlan.waves().size() > waveLimit) {
                int hiddenWaves = wavePlan.waves().size() - waveLimit;
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL finalDensity chunk waves: +" + hiddenWaves + " more wave(s) hidden."),
                        false);
            }
            return countTrue(wavePlan.scheduledChunks());
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL finalDensity chunk waves: " + formatThrowable(throwable)));
            return 0;
        }
    }

    private static int runOpenClCompiledFinalDensityWaveFusedCheck(CommandSourceStack source, int cellWidth,
                                                                   int cellHeight, int cells,
                                                                   boolean hybridFinalDensity) {
        DfcOpenClRuntime.OpenClCompiledPlan plan;
        List<OpenClCompiledPlanChunk> chunks;
        OpenClChunkWavePlan wavePlan;
        int[] chunkStartSlots;
        int[] chunkEndSlots;
        try {
            plan = collectOpenClCompiledFinalDensityPlan(source);
            chunks = new ArrayList<>();
            collectOpenClCompiledPlanChunks(plan, chunks, new ArrayList<>());
            int slots = plan.specs() == null ? 0 : plan.specs().length;
            int[] slotOwners = buildOpenClChunkSlotOwners(chunks, slots);
            List<boolean[]> chunkInputs = new ArrayList<>(chunks.size());
            for (OpenClCompiledPlanChunk chunk : chunks) {
                chunkInputs.add(DfcOpenClRuntime.compiledPlanChunkExternalInputs(
                        plan, chunk.startSlot(), chunk.endSlot()));
            }
            wavePlan = collectOpenClChunkWaves(chunkInputs, slotOwners);
            chunkStartSlots = new int[chunks.size()];
            chunkEndSlots = new int[chunks.size()];
            for (int i = 0; i < chunks.size(); i++) {
                chunkStartSlots[i] = chunks.get(i).startSlot();
                chunkEndSlots[i] = chunks.get(i).endSlot();
            }
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL finalDensity "
                            + (hybridFinalDensity ? "hybrid" : "wave fused")
                            + " check: " + formatThrowable(throwable)));
            return 0;
        }

        String label = "finalDensity " + (hybridFinalDensity ? "hybrid" : "wave fused") + " check";
        boolean[][] waves = wavePlan.waves().toArray(new boolean[0][]);
        int scheduledChunks = countTrue(wavePlan.scheduledChunks());
        int scheduledSlots = countOpenClScheduledChunkSlots(chunks, wavePlan.scheduledChunks());
        int totalChunks = chunks.size();
        int directBlockedChunks = countTrue(wavePlan.directBlockedChunks());
        int stalledChunks = countTrue(wavePlan.stalledChunks());
        return runOpenClDiagnostic(source, label, () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.compiledPlanChunkWavesFusedCompactSourceCheck(
                            plan, chunkStartSlots, chunkEndSlots, waves,
                            directBlockedChunks, stalledChunks,
                            cellWidth, cellHeight, cells);
            return Component.literal(
                    "DFC OpenCL " + label + ": passed=" + result.passed()
                            + ", waves=" + waves.length
                            + ", chunks=" + scheduledChunks + "/" + totalChunks
                            + ", slotsComputed=" + scheduledSlots
                            + ", directBlocked=" + describeOpenClSlotSet(wavePlan.directBlockedChunks(), 12)
                            + ", stalled=" + describeOpenClSlotSet(wavePlan.stalledChunks(), 12)
                            + ", cellWidth=" + result.cellWidth()
                            + ", cellHeight=" + result.cellHeight()
                            + ", cells=" + result.cells()
                            + ", elements=" + result.elementsPerIteration()
                            + ", elapsedMs=" + formatNanosMillis(result.averageNanos())
                            + ", slotValueNs=" + formatAverageNanos(result.totalNanos(), result.totalElements())
                            + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                            + ", message=" + result.message());
        });
    }

    private static String openClWaveBenchLabel(boolean compactSlotBuffer, boolean fusedWaves,
                                               boolean allWavesFused) {
        if (allWavesFused) {
            return "all waves fused";
        }
        if (fusedWaves) {
            return "wave fused";
        }
        return "waves" + (compactSlotBuffer ? " compact" : "");
    }

    private static int runOpenClCompiledFinalDensityWavesBench(CommandSourceStack source, int cellWidth,
                                                               int cellHeight, int cells, int iterations,
                                                               int warmups, boolean compactSlotBuffer,
                                                               boolean fusedWaves, boolean allWavesFused) {
        DfcOpenClRuntime.OpenClCompiledPlan plan;
        List<OpenClCompiledPlanChunk> chunks;
        OpenClChunkWavePlan wavePlan;
        int[] chunkStartSlots;
        int[] chunkEndSlots;
        try {
            plan = collectOpenClCompiledFinalDensityPlan(source);
            chunks = new ArrayList<>();
            collectOpenClCompiledPlanChunks(plan, chunks, new ArrayList<>());
            int slots = plan.specs() == null ? 0 : plan.specs().length;
            int[] slotOwners = buildOpenClChunkSlotOwners(chunks, slots);
            List<boolean[]> chunkInputs = new ArrayList<>(chunks.size());
            for (OpenClCompiledPlanChunk chunk : chunks) {
                chunkInputs.add(DfcOpenClRuntime.compiledPlanChunkExternalInputs(
                        plan, chunk.startSlot(), chunk.endSlot()));
            }
            wavePlan = collectOpenClChunkWaves(chunkInputs, slotOwners);
            chunkStartSlots = new int[chunks.size()];
            chunkEndSlots = new int[chunks.size()];
            for (int i = 0; i < chunks.size(); i++) {
                chunkStartSlots[i] = chunks.get(i).startSlot();
                chunkEndSlots[i] = chunks.get(i).endSlot();
            }
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL finalDensity "
                            + openClWaveBenchLabel(compactSlotBuffer, fusedWaves, allWavesFused)
                            + " bench: " + formatThrowable(throwable)));
            return 0;
        }

        String label = "finalDensity " + openClWaveBenchLabel(
                compactSlotBuffer, fusedWaves, allWavesFused) + " bench";
        boolean[][] waves = wavePlan.waves().toArray(new boolean[0][]);
        int scheduledChunks = countTrue(wavePlan.scheduledChunks());
        int scheduledSlots = countOpenClScheduledChunkSlots(chunks, wavePlan.scheduledChunks());
        int totalChunks = chunks.size();
        int directBlockedChunks = countTrue(wavePlan.directBlockedChunks());
        int stalledChunks = countTrue(wavePlan.stalledChunks());
        return runOpenClDiagnostic(source, label, () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result = allWavesFused
                    ? DfcOpenClRuntime.compiledPlanChunkAllWavesFusedCompactSourceBenchmark(
                    plan, chunkStartSlots, chunkEndSlots, waves,
                    directBlockedChunks, stalledChunks,
                    cellWidth, cellHeight, cells, iterations, warmups)
                    : fusedWaves
                    ? DfcOpenClRuntime.compiledPlanChunkWavesFusedCompactSourceBenchmark(
                    plan, chunkStartSlots, chunkEndSlots, waves,
                    directBlockedChunks, stalledChunks,
                    cellWidth, cellHeight, cells, iterations, warmups)
                    : compactSlotBuffer
                    ? DfcOpenClRuntime.compiledPlanChunkWavesCompactSourceBenchmark(
                    plan, chunkStartSlots, chunkEndSlots, waves,
                    directBlockedChunks, stalledChunks,
                    cellWidth, cellHeight, cells, iterations, warmups)
                    : DfcOpenClRuntime.compiledPlanChunkWavesSourceBenchmark(
                            plan, chunkStartSlots, chunkEndSlots, waves,
                            directBlockedChunks, stalledChunks,
                            cellWidth, cellHeight, cells, iterations, warmups);
            return Component.literal(
                    "DFC OpenCL " + label + ": passed=" + result.passed()
                            + ", waves=" + waves.length
                            + ", chunks=" + scheduledChunks + "/" + totalChunks
                            + ", slotsComputed=" + scheduledSlots
                            + ", directBlocked=" + describeOpenClSlotSet(wavePlan.directBlockedChunks(), 12)
                            + ", stalled=" + describeOpenClSlotSet(wavePlan.stalledChunks(), 12)
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
                            + ", avgSlotValueNs=" + formatAverageNanos(result.totalNanos(), result.totalElements())
                            + ", bestSlotValueNs=" + formatAverageNanos(
                            result.bestNanos(), result.elementsPerIteration())
                            + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClCompiledFinalDensityChunkCompileProbe(CommandSourceStack source, int chunkIndex) {
        DfcOpenClRuntime.OpenClCompiledPlan plan;
        OpenClCompiledPlanChunk chunk;
        try {
            plan = collectOpenClCompiledFinalDensityPlan(source);
            List<OpenClCompiledPlanChunk> chunks = new ArrayList<>();
            collectOpenClCompiledPlanChunks(plan, chunks, new ArrayList<>());
            if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
                source.sendFailure(Component.literal(
                        "DFC OpenCL finalDensity chunk source compile: chunk " + chunkIndex
                                + " is out of range 0.." + Math.max(0, chunks.size() - 1)));
                return 0;
            }
            chunk = chunks.get(chunkIndex);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL finalDensity chunk source compile: " + formatThrowable(throwable)));
            return 0;
        }

        return runOpenClDiagnostic(source, "finalDensity chunk source compile", () -> {
            DfcOpenClRuntime.GeneratedSourceCompileProbe result =
                    DfcOpenClRuntime.compiledPlanChunkSourceCompileProbe(
                            plan, chunk.startSlot(), chunk.endSlot());
            boolean[] inputs = DfcOpenClRuntime.compiledPlanChunkExternalInputs(
                    plan, chunk.startSlot(), chunk.endSlot());
            return Component.literal(
                    "DFC OpenCL finalDensity chunk source compile: passed=" + result.passed()
                            + ", chunk=" + chunkIndex
                            + ", " + describeOpenClCompiledPlanChunk(chunk)
                            + ", inputs=" + describeOpenClSlotSet(inputs, 12)
                            + ", compileMs=" + formatNanosMillis(result.compileNanos())
                            + ", sourceChars=" + result.sourceChars()
                            + ", totalNoiseOctaves=" + result.totalNoiseOctaves()
                            + ", coordTemps=" + result.coordScaleTemps()
                            + ", coordTempRefs=" + result.coordScaleRefs()
                            + ", device=" + (result.device() == null ? "none" : result.device().shortDescription())
                            + ", message=" + result.message());
        });
    }

    private static int runOpenClCompiledFinalDensityChunkBench(CommandSourceStack source, int cellWidth,
                                                               int cellHeight, int cells, int iterations,
                                                               int warmups, int chunkIndex) {
        DfcOpenClRuntime.OpenClCompiledPlan plan;
        OpenClCompiledPlanChunk chunk;
        try {
            plan = collectOpenClCompiledFinalDensityPlan(source);
            List<OpenClCompiledPlanChunk> chunks = new ArrayList<>();
            collectOpenClCompiledPlanChunks(plan, chunks, new ArrayList<>());
            if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
                source.sendFailure(Component.literal(
                        "DFC OpenCL finalDensity chunk source bench: chunk " + chunkIndex
                                + " is out of range 0.." + Math.max(0, chunks.size() - 1)));
                return 0;
            }
            chunk = chunks.get(chunkIndex);
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL finalDensity chunk source bench: " + formatThrowable(throwable)));
            return 0;
        }

        return runOpenClDiagnostic(source, "finalDensity chunk source bench", () -> {
            DfcOpenClRuntime.SlabVmCellBenchmark result =
                    DfcOpenClRuntime.compiledPlanChunkSourceBenchmark(
                            plan, chunk.startSlot(), chunk.endSlot(),
                            cellWidth, cellHeight, cells, iterations, warmups);
            boolean[] inputs = DfcOpenClRuntime.compiledPlanChunkExternalInputs(
                    plan, chunk.startSlot(), chunk.endSlot());
            return Component.literal(
                    "DFC OpenCL finalDensity chunk source bench: passed=" + result.passed()
                            + ", chunk=" + chunkIndex
                            + ", " + describeOpenClCompiledPlanChunk(chunk)
                            + ", inputs=" + describeOpenClSlotSet(inputs, 12)
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

    private static int sendOpenClCompiledPlanCandidates(CommandSourceStack source) {
        try {
            NoiseRouter router = source.getLevel().getChunkSource().randomState().router();
            List<String> entries = new ArrayList<>();
            DfcOpenClRuntime.OpenClCompiledPlan best = null;
            DfcOpenClRuntime.OpenClCompiledPlan sourceBest = null;
            int ok = 0;
            for (RouterDensityCandidate candidate : openClRouterCandidates(router)) {
                List<String> failures = new ArrayList<>();
                DfcOpenClRuntime.OpenClCompiledPlan plan = tryCollectOpenClCompiledPlan(candidate, failures);
                if (plan != null) {
                    ok++;
                    best = betterOpenClCompiledPlan(best, plan);
                    if (openClCompiledPlanSourceBenchSafe(plan)) {
                        sourceBest = betterOpenClCompiledPlan(sourceBest, plan);
                    }
                    entries.add(describeOpenClCompiledPlan(plan));
                } else {
                    entries.add(failures.isEmpty() ? candidate.name() + ": fail"
                            : failures.get(0));
                }
            }
            List<String> syntheticFailures = new ArrayList<>();
            DfcOpenClRuntime.OpenClCompiledPlan synthetic = tryCollectSyntheticOpenClCompiledPlan(
                    source, syntheticFailures);
            if (synthetic != null) {
                ok++;
                entries.add(describeOpenClCompiledPlan(synthetic));
                if (best == null) {
                    best = synthetic;
                }
                if (sourceBest == null && openClCompiledPlanSourceBenchSafe(synthetic)) {
                    sourceBest = synthetic;
                }
            } else if (!syntheticFailures.isEmpty()) {
                entries.add(syntheticFailures.get(0));
            }

            DfcOpenClRuntime.OpenClCompiledPlan selected = best;
            DfcOpenClRuntime.OpenClCompiledPlan sourceSelected = sourceBest;
            int okCount = ok;
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL compiled plan candidates: ok=" + okCount
                            + ", selected=" + (selected == null ? "none" : selected.label())
                            + (selected == null ? "" : ", score=" + openClCompiledPlanScore(selected))
                            + ", sourceSelected="
                            + (sourceSelected == null ? "none" : sourceSelected.label())),
                    false);
            int perLine = 4;
            for (int i = 0; i < entries.size(); i += perLine) {
                int start = i;
                int end = Math.min(entries.size(), i + perLine);
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL compiled plan candidates[" + start + ".." + (end - 1) + "]: "
                                + String.join(" | ", entries.subList(start, end))),
                        false);
            }
            return ok;
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL compiled plan candidates: " + formatThrowable(throwable)));
            return 0;
        }
    }

    private static int sendOpenClCompiledPlanExterns(CommandSourceStack source) {
        try {
            DfcOpenClRuntime.OpenClCompiledPlan plan = collectOpenClCompiledRouterPlan(source);
            int slots = plan.specs() == null ? 0 : plan.specs().length;
            int external = countExternalSlots(plan.externalSlots(), slots);
            int computed = countComputedSlots(plan.computedSlots(), slots);
            source.sendSuccess(() -> Component.literal(
                    "DFC OpenCL compiled plan externs: label=" + plan.label()
                            + ", slots=" + slots
                            + ", gpu=" + Math.max(0, slots - external)
                            + ", external=" + external
                            + ", computed=" + computed),
                    false);
            if (external == 0) {
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL compiled plan externs: no unresolved external slots."), false);
                return 0;
            }

            int sent = 0;
            boolean[] externalSlots = plan.externalSlots();
            for (int slot = 0; slot < slots; slot++) {
                if (externalSlots == null || slot >= externalSlots.length || !externalSlots[slot]) {
                    continue;
                }
                int externalSlot = slot;
                String line = describeOpenClCompiledExternalSlot(plan, slot);
                source.sendSuccess(() -> Component.literal(
                        "DFC OpenCL compiled plan extern[" + externalSlot + "]: " + line), false);
                sent++;
            }
            return sent;
        } catch (Throwable throwable) {
            source.sendFailure(Component.literal(
                    "DFC OpenCL compiled plan externs: " + formatThrowable(throwable)));
            return 0;
        }
    }

    private static RouterDensityCandidate[] openClRouterCandidates(NoiseRouter router) {
        return new RouterDensityCandidate[]{
                new RouterDensityCandidate("finalDensity", router.finalDensity()),
                new RouterDensityCandidate("initialDensityWithoutJaggedness", router.initialDensityWithoutJaggedness()),
                new RouterDensityCandidate("depth", router.depth()),
                new RouterDensityCandidate("ridges", router.ridges()),
                new RouterDensityCandidate("continents", router.continents()),
                new RouterDensityCandidate("erosion", router.erosion()),
                new RouterDensityCandidate("temperature", router.temperature()),
                new RouterDensityCandidate("vegetation", router.vegetation()),
                new RouterDensityCandidate("veinToggle", router.veinToggle()),
                new RouterDensityCandidate("veinRidged", router.veinRidged()),
                new RouterDensityCandidate("veinGap", router.veinGap()),
                new RouterDensityCandidate("barrierNoise", router.barrierNoise()),
                new RouterDensityCandidate("fluidLevelFloodednessNoise", router.fluidLevelFloodednessNoise()),
                new RouterDensityCandidate("fluidLevelSpreadNoise", router.fluidLevelSpreadNoise()),
                new RouterDensityCandidate("lavaNoise", router.lavaNoise()),
        };
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan betterOpenClCompiledPlan(
            DfcOpenClRuntime.OpenClCompiledPlan left, DfcOpenClRuntime.OpenClCompiledPlan right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return openClCompiledPlanScore(right) > openClCompiledPlanScore(left) ? right : left;
    }

    private static boolean openClCompiledPlanSourceBenchSafe(DfcOpenClRuntime.OpenClCompiledPlan plan) {
        return openClCompiledPlanSourceBenchRejection(plan) == null;
    }

    private static String openClCompiledPlanSourceBenchRejection(DfcOpenClRuntime.OpenClCompiledPlan plan) {
        if (plan == null || plan.specs() == null) {
            return "missing compiled plan specs";
        }
        int slots = plan.specs().length;
        int octaves = countActiveOctaves(plan.specs(), plan.blendedSpecs());
        int computed = countComputedSlots(plan.computedSlots(), slots);
        int external = countExternalSlots(plan.externalSlots(), slots);
        List<String> reasons = new ArrayList<>(4);
        if (slots > OPENCL_SOURCE_BENCH_MAX_SLOTS) {
            reasons.add("slots " + slots + ">" + OPENCL_SOURCE_BENCH_MAX_SLOTS);
        }
        if (octaves > OPENCL_SOURCE_BENCH_MAX_OCTAVES) {
            reasons.add("octaves " + octaves + ">" + OPENCL_SOURCE_BENCH_MAX_OCTAVES);
        }
        if (computed > OPENCL_SOURCE_BENCH_MAX_COMPUTED) {
            reasons.add("computed " + computed + ">" + OPENCL_SOURCE_BENCH_MAX_COMPUTED);
        }
        if (external > 0) {
            reasons.add("external " + external + ">0");
        }
        return reasons.isEmpty() ? null : String.join(", ", reasons);
    }

    private static long openClCompiledPlanScore(DfcOpenClRuntime.OpenClCompiledPlan plan) {
        if (plan == null) {
            return Long.MIN_VALUE;
        }
        long slots = plan.specs() == null ? 0L : plan.specs().length;
        long octaves = countActiveOctaves(plan.specs(), plan.blendedSpecs());
        long programBytes = plan.slabProgram() == null ? 0L : plan.slabProgram().length;
        long constants = plan.slabConstants() == null ? 0L : plan.slabConstants().length;
        return slots * 1_000_000L + octaves * 10_000L + programBytes * 100L + constants;
    }

    private static String describeOpenClCompiledPlan(DfcOpenClRuntime.OpenClCompiledPlan plan) {
        int slots = plan.specs() == null ? 0 : plan.specs().length;
        int externalSlots = countExternalSlots(plan.externalSlots(), slots);
        int computedSlots = countComputedSlots(plan.computedSlots(), slots);
        int gpuSlots = Math.max(0, slots - externalSlots);
        return plan.label()
                + ": score=" + openClCompiledPlanScore(plan)
                + ", slots=" + slots
                + ", gpu=" + gpuSlots
                + ", external=" + externalSlots
                + ", computed=" + computedSlots
                + ", octaves=" + countActiveOctaves(plan.specs(), plan.blendedSpecs())
                + ", bc=" + (plan.slabProgram() == null ? 0 : plan.slabProgram().length)
                + ", consts=" + (plan.slabConstants() == null ? 0 : plan.slabConstants().length)
                + ", slotCoords=" + (plan.slotCoordXExpressions() != null);
    }

    private static String describeOpenClCompiledPlanSourceLimits(DfcOpenClRuntime.OpenClCompiledPlan plan) {
        int slots = plan.specs() == null ? 0 : plan.specs().length;
        return "label=" + plan.label()
                + ", slots=" + slots + "/" + OPENCL_SOURCE_BENCH_MAX_SLOTS
                + ", octaves=" + countActiveOctaves(plan.specs(), plan.blendedSpecs())
                + "/" + OPENCL_SOURCE_BENCH_MAX_OCTAVES
                + ", computed=" + countComputedSlots(plan.computedSlots(), slots)
                + "/" + OPENCL_SOURCE_BENCH_MAX_COMPUTED
                + ", external=" + countExternalSlots(plan.externalSlots(), slots) + "/0";
    }

    private static void collectOpenClCompiledPlanChunks(DfcOpenClRuntime.OpenClCompiledPlan plan,
                                                        List<OpenClCompiledPlanChunk> chunks,
                                                        List<Integer> blockedSlots) {
        int slots = plan.specs() == null ? 0 : plan.specs().length;
        int start = -1;
        int count = 0;
        int octaves = 0;
        int computed = 0;
        for (int slot = 0; slot < slots; slot++) {
            int slotOctaves = countActiveOctaves(plan, slot);
            int slotComputed = hasComputedSlot(plan.computedSlots(), slot) ? 1 : 0;
            if (openClCompiledPlanSlotChunkRejection(plan, slot) != null) {
                if (count > 0) {
                    chunks.add(new OpenClCompiledPlanChunk(start, slot - 1, count, octaves, computed));
                    start = -1;
                    count = 0;
                    octaves = 0;
                    computed = 0;
                }
                blockedSlots.add(slot);
                continue;
            }
            if (count > 0
                    && (count + 1 > OPENCL_FINAL_CHUNK_MAX_SLOTS
                    || octaves + slotOctaves > OPENCL_FINAL_CHUNK_MAX_OCTAVES
                    || computed + slotComputed > OPENCL_FINAL_CHUNK_MAX_COMPUTED)) {
                chunks.add(new OpenClCompiledPlanChunk(start, slot - 1, count, octaves, computed));
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
            chunks.add(new OpenClCompiledPlanChunk(start, slots - 1, count, octaves, computed));
        }
    }

    private static String describeOpenClCompiledPlanChunk(OpenClCompiledPlanChunk chunk) {
        return "slots=" + chunk.startSlot() + ".." + chunk.endSlot()
                + ", count=" + chunk.count() + "/" + OPENCL_FINAL_CHUNK_MAX_SLOTS
                + ", octaves=" + chunk.octaves() + "/" + OPENCL_FINAL_CHUNK_MAX_OCTAVES
                + ", computed=" + chunk.computed() + "/" + OPENCL_FINAL_CHUNK_MAX_COMPUTED
                + ", external=0";
    }

    private static String describeOpenClSlotSet(boolean[] slots, int limit) {
        int total = countTrue(slots);
        if (total == 0) {
            return "0[]";
        }
        StringBuilder out = new StringBuilder();
        out.append(total).append('[');
        int emitted = 0;
        for (int slot = 0; slot < slots.length && emitted < limit; slot++) {
            if (!slots[slot]) {
                continue;
            }
            if (emitted > 0) {
                out.append(',');
            }
            out.append(slot);
            emitted++;
        }
        if (total > emitted) {
            out.append(",+").append(total - emitted);
        }
        out.append(']');
        return out.toString();
    }

    private static int[] buildOpenClChunkSlotOwners(List<OpenClCompiledPlanChunk> chunks, int slots) {
        int[] owners = new int[Math.max(0, slots)];
        Arrays.fill(owners, -1);
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            OpenClCompiledPlanChunk chunk = chunks.get(chunkIndex);
            int start = Math.max(0, chunk.startSlot());
            int end = Math.min(owners.length - 1, chunk.endSlot());
            for (int slot = start; slot <= end; slot++) {
                owners[slot] = chunkIndex;
            }
        }
        return owners;
    }

    static String describeOpenClChunkProducerSet(boolean[] inputs, int[] slotOwners, int limit) {
        return describeOpenClSlotSet(openClChunkProducerMask(inputs, slotOwners), limit);
    }

    static String describeOpenClBlockedInputSet(boolean[] inputs, int[] slotOwners, int limit) {
        return describeOpenClSlotSet(openClBlockedInputMask(inputs, slotOwners), limit);
    }

    private static int countOpenClChunkProducerChunks(boolean[] inputs, int[] slotOwners) {
        return countTrue(openClChunkProducerMask(inputs, slotOwners));
    }

    private static int countOpenClBlockedInputs(boolean[] inputs, int[] slotOwners) {
        return countTrue(openClBlockedInputMask(inputs, slotOwners));
    }

    private static int countOpenClScheduledChunkSlots(List<OpenClCompiledPlanChunk> chunks, boolean[] scheduledChunks) {
        int count = 0;
        int limit = Math.min(chunks.size(), scheduledChunks == null ? 0 : scheduledChunks.length);
        for (int chunk = 0; chunk < limit; chunk++) {
            if (scheduledChunks[chunk]) {
                count += chunks.get(chunk).count();
            }
        }
        return count;
    }

    static OpenClChunkWavePlan collectOpenClChunkWaves(List<boolean[]> chunkInputs, int[] slotOwners) {
        int chunkCount = chunkInputs == null ? 0 : chunkInputs.size();
        boolean[] scheduledChunks = new boolean[chunkCount];
        boolean[] directBlockedChunks = new boolean[chunkCount];
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            directBlockedChunks[chunk] = countOpenClBlockedInputs(chunkInputs.get(chunk), slotOwners) > 0;
        }

        List<boolean[]> waves = new ArrayList<>();
        while (true) {
            boolean[] wave = new boolean[chunkCount];
            int waveChunks = 0;
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                if (scheduledChunks[chunk] || directBlockedChunks[chunk]) {
                    continue;
                }
                if (openClChunkInputsReady(chunkInputs.get(chunk), slotOwners, scheduledChunks)) {
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

        boolean[] stalledChunks = new boolean[chunkCount];
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            stalledChunks[chunk] = !scheduledChunks[chunk] && !directBlockedChunks[chunk];
        }
        return new OpenClChunkWavePlan(waves, scheduledChunks, directBlockedChunks, stalledChunks);
    }

    private static boolean openClChunkInputsReady(boolean[] inputs, int[] slotOwners, boolean[] scheduledChunks) {
        if (inputs == null) {
            return true;
        }
        for (int slot = 0; slot < inputs.length; slot++) {
            if (!inputs[slot]) {
                continue;
            }
            int owner = openClSlotOwner(slotOwners, slot);
            if (owner < 0 || owner >= scheduledChunks.length || !scheduledChunks[owner]) {
                return false;
            }
        }
        return true;
    }

    private static boolean[] collectOpenClBlockedInputUnion(List<boolean[]> chunkInputs, int[] slotOwners) {
        int slots = slotOwners == null ? 0 : slotOwners.length;
        boolean[] blockedInputs = new boolean[slots];
        if (chunkInputs == null) {
            return blockedInputs;
        }
        for (boolean[] inputs : chunkInputs) {
            boolean[] chunkBlockedInputs = openClBlockedInputMask(inputs, slotOwners);
            int limit = Math.min(blockedInputs.length, chunkBlockedInputs.length);
            for (int slot = 0; slot < limit; slot++) {
                blockedInputs[slot] |= chunkBlockedInputs[slot];
            }
        }
        return blockedInputs;
    }

    private static boolean[] openClChunkProducerMask(boolean[] inputs, int[] slotOwners) {
        int maxOwner = -1;
        if (slotOwners != null) {
            for (int owner : slotOwners) {
                maxOwner = Math.max(maxOwner, owner);
            }
        }
        boolean[] producers = new boolean[Math.max(0, maxOwner + 1)];
        if (inputs == null) {
            return producers;
        }
        for (int slot = 0; slot < inputs.length; slot++) {
            if (!inputs[slot]) {
                continue;
            }
            int owner = openClSlotOwner(slotOwners, slot);
            if (owner >= 0 && owner < producers.length) {
                producers[owner] = true;
            }
        }
        return producers;
    }

    private static boolean[] openClBlockedInputMask(boolean[] inputs, int[] slotOwners) {
        if (inputs == null) {
            return new boolean[0];
        }
        boolean[] blockedInputs = new boolean[inputs.length];
        for (int slot = 0; slot < inputs.length; slot++) {
            blockedInputs[slot] = inputs[slot] && openClSlotOwner(slotOwners, slot) < 0;
        }
        return blockedInputs;
    }

    private static int openClSlotOwner(int[] slotOwners, int slot) {
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

    private static String describeOpenClCompiledPlanBlockedSlot(
            DfcOpenClRuntime.OpenClCompiledPlan plan, int slot) {
        StringBuilder out = new StringBuilder();
        out.append("slot=").append(slot)
                .append(", octaves=").append(countActiveOctaves(plan, slot))
                .append(", computed=").append(hasComputedSlot(plan.computedSlots(), slot) ? 1 : 0)
                .append(", external=").append(isExternalSlot(plan.externalSlots(), slot));
        if (isExternalSlot(plan.externalSlots(), slot)) {
            out.append(", ").append(describeOpenClCompiledExternalSlot(plan, slot));
        }
        out.append(", reason=").append(openClCompiledPlanSlotChunkRejection(plan, slot));
        return out.toString();
    }

    private static String openClCompiledPlanSlotChunkRejection(
            DfcOpenClRuntime.OpenClCompiledPlan plan, int slot) {
        List<String> reasons = new ArrayList<>(4);
        int octaves = countActiveOctaves(plan, slot);
        int computed = hasComputedSlot(plan.computedSlots(), slot) ? 1 : 0;
        if (octaves > OPENCL_FINAL_CHUNK_MAX_OCTAVES) {
            reasons.add("octaves " + octaves + ">" + OPENCL_FINAL_CHUNK_MAX_OCTAVES);
        }
        if (computed > OPENCL_FINAL_CHUNK_MAX_COMPUTED) {
            reasons.add("computed " + computed + ">" + OPENCL_FINAL_CHUNK_MAX_COMPUTED);
        }
        if (isExternalSlot(plan.externalSlots(), slot)) {
            reasons.add("external");
        }
        return reasons.isEmpty() ? null : String.join(", ", reasons);
    }

    private static int countExternalSlots(boolean[] externalSlots) {
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

    private static int countExternalSlots(boolean[] externalSlots, int usedSlotCount) {
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

    private static boolean isExternalSlot(boolean[] externalSlots, int slot) {
        return externalSlots != null && slot >= 0 && slot < externalSlots.length && externalSlots[slot];
    }

    private static boolean hasComputedSlot(DfcOpenClRuntime.ComputedSlot[] computedSlots, int slot) {
        return computedSlots != null && slot >= 0 && slot < computedSlots.length && computedSlots[slot] != null;
    }

    private static int countComputedSlots(DfcOpenClRuntime.ComputedSlot[] computedSlots, int usedSlotCount) {
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

    private static String describeOpenClCompiledExternalSlot(DfcOpenClRuntime.OpenClCompiledPlan plan, int slot) {
        int[] markerExternIndices = plan.markerExternIndices();
        DensityFunction[] externs = plan.externs();
        if (markerExternIndices == null || slot < 0 || slot >= markerExternIndices.length) {
            return "missing marker extern index";
        }
        int externIndex = markerExternIndices[slot];
        if (externIndex < 0 || externs == null || externIndex >= externs.length || externs[externIndex] == null) {
            return "invalid externIndex=" + externIndex;
        }
        DensityFunction extern = externs[externIndex];
        StringBuilder out = new StringBuilder();
        out.append("externIndex=").append(externIndex)
                .append(", externClass=").append(shortClassName(extern));
        if (extern instanceof DensityFunctions.MarkerOrMarked marker) {
            DensityFunction wrapped = marker.wrapped();
            out.append(", markerType=").append(marker.type())
                    .append(", wrappedClass=").append(shortClassName(wrapped))
                    .append(", wrappedPlan=").append(describeWrappedOpenClPlan(wrapped));
        }
        return out.toString();
    }

    private static String describeWrappedOpenClPlan(DensityFunction wrapped) {
        if (wrapped == null) {
            return "null";
        }
        try {
            CompiledDensityFunction compiled = null;
            if (wrapped instanceof CompiledDensityFunction c) {
                compiled = c;
            } else {
                Compiler.Result result = Compiler.compileWithDetail(wrapped);
                if (result != null) {
                    compiled = result.compiled();
                }
            }
            if (compiled == null) {
                return "did not compile";
            }
            DfcOpenClCompiledPlanRegistry.Entry entry = DfcOpenClCompiledPlanRegistry.lookup(compiled);
            if (!entry.available()) {
                return entry.unavailableReason();
            }
            DfcOpenClRuntime.OpenClCompiledPlan expanded =
                    DfcOpenClCompiledPlanRegistry.expandMarkerSlots(
                            entry.plan(), OPENCL_COMPILED_PLAN_MARKER_EXPAND_DEPTH);
            return describeOpenClCompiledPlan(expanded);
        } catch (Throwable throwable) {
            return formatThrowable(throwable);
        }
    }

    private static String shortClassName(Object object) {
        if (object == null) {
            return "null";
        }
        String name = object.getClass().getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan tryCollectOpenClCompiledPlan(
            RouterDensityCandidate candidate, List<String> failures) {
        try {
            DensityFunction function = candidate.function();
            CompiledDensityFunction compiled = null;
            if (function instanceof CompiledDensityFunction c) {
                compiled = c;
            } else if (function != null) {
                DensityFunction compiledFunction = CompilingVisitor.global().apply(function);
                if (compiledFunction instanceof CompiledDensityFunction c) {
                    compiled = c;
                }
            }
            if (compiled == null) {
                failures.add(candidate.name() + ": did not compile to CompiledDensityFunction");
                return null;
            }

            DfcOpenClCompiledPlanRegistry.Entry entry = DfcOpenClCompiledPlanRegistry.lookup(compiled);
            if (entry.available()) {
                DfcOpenClRuntime.OpenClCompiledPlan expanded =
                        DfcOpenClCompiledPlanRegistry.expandMarkerSlots(
                                entry.plan(), OPENCL_COMPILED_PLAN_MARKER_EXPAND_DEPTH);
                return labelOpenClCompiledPlan(candidate.name(), expanded);
            }
            failures.add(candidate.name() + ": " + entry.unavailableReason());
            return null;
        } catch (Throwable throwable) {
            failures.add(candidate.name() + ": " + formatThrowable(throwable));
            return null;
        }
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan tryCollectSyntheticOpenClCompiledPlan(
            CommandSourceStack source, List<String> failures) {
        String name = "syntheticRealNoise";
        try {
            NormalNoise[] noises = collectOpenClRealNoises(source, 8);
            DensityFunction graph = buildSyntheticOpenClCompiledDensity(noises);
            Compiler.Result result = Compiler.compileWithDetail(graph);
            if (result == null) {
                failures.add(name + ": synthetic graph did not compile");
                return null;
            }
            DfcOpenClCompiledPlanRegistry.Entry entry =
                    DfcOpenClCompiledPlanRegistry.lookup(result.compiled());
            if (entry.available()) {
                return labelOpenClCompiledPlan(name, entry.plan());
            }
            failures.add(name + ": " + entry.unavailableReason());
            return null;
        } catch (Throwable throwable) {
            failures.add(name + ": " + formatThrowable(throwable));
            return null;
        }
    }

    private static DensityFunction buildSyntheticOpenClCompiledDensity(NormalNoise[] noises) {
        DensityFunction sum = DensityFunctions.constant(0.0D);
        for (int i = 0; i < noises.length; i++) {
            DensityFunction noise = new DensityFunctions.Noise(
                    new DensityFunction.NoiseHolder(null, noises[i]),
                    1.0D + i * 0.03125D,
                    1.0D + i * 0.015625D);
            if (i != 0) {
                noise = DensityFunctions.mul(noise, DensityFunctions.constant(1.0D / (i + 1.0D)));
            }
            sum = DensityFunctions.add(sum, noise);
        }

        DensityFunction yHoist = DensityFunctions.yClampedGradient(-64, 320, -1.0D, 1.0D);
        yHoist = DensityFunctions.add(yHoist, DensityFunctions.constant(0.125D));
        yHoist = yHoist.clamp(-0.75D, 0.75D).square().squeeze();
        return DensityFunctions.add(sum, yHoist);
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan labelOpenClCompiledPlan(
            String fieldName, DfcOpenClRuntime.OpenClCompiledPlan plan) {
        return new DfcOpenClRuntime.OpenClCompiledPlan(
                fieldName + "/" + plan.label(),
                plan.specs(),
                plan.slabProgram(),
                plan.slabConstants(),
                plan.hoistExpression(),
                plan.hoistEvaluator(),
                plan.slotCoordXExpressions(),
                plan.slotCoordYExpressions(),
                plan.slotCoordZExpressions(),
                plan.slotCoordXEvaluators(),
                plan.slotCoordYEvaluators(),
                plan.slotCoordZEvaluators(),
                plan.blendedSpecs(),
                plan.externalSlots(),
                plan.markerExternIndices(),
                plan.externs(),
                plan.computedSlots());
    }

    private static String limitedFailureSummary(List<String> failures, int limit) {
        if (failures.isEmpty()) {
            return "no candidates were checked";
        }
        int count = Math.min(Math.max(1, limit), failures.size());
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                summary.append("; ");
            }
            summary.append(failures.get(i));
        }
        if (failures.size() > count) {
            summary.append("; +").append(failures.size() - count).append(" more");
        }
        return summary.toString();
    }

    private record RouterDensityCandidate(String name, DensityFunction function) {
    }

    private record OpenClCompiledPlanChunk(int startSlot, int endSlot, int count, int octaves, int computed) {
    }

    record OpenClChunkWavePlan(List<boolean[]> waves, boolean[] scheduledChunks, boolean[] directBlockedChunks,
                               boolean[] stalledChunks) {
    }

    private static NormalNoise[] collectOpenClRealNoises(CommandSourceStack source, int requestedSlots) {
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
        List<NormalNoise> noises = new ArrayList<>(target);
        for (DensityFunction field : fields) {
            CompiledDensityFunction compiled = null;
            if (field instanceof CompiledDensityFunction c) {
                compiled = c;
            } else {
                try {
                    DensityFunction compiledField = CompilingVisitor.global().apply(field);
                    if (compiledField instanceof CompiledDensityFunction c) {
                        compiled = c;
                    }
                } catch (Throwable ignored) {
                    compiled = null;
                }
            }
            if (compiled == null) {
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
                noises.add(noise);
                if (noises.size() >= target) {
                    return noises.toArray(new NormalNoise[0]);
                }
            }
        }
        if (noises.size() < 2) {
            throw new IllegalStateException("found " + noises.size()
                    + " real NormalNoise instances in compiled router; enter a world with DFC compiled router first");
        }
        return noises.toArray(new NormalNoise[0]);
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

    private static int countActiveOctaves(DfcOpenClRuntime.OpenClCompiledPlan plan, int slot) {
        if (plan == null || slot < 0) {
            return 0;
        }
        int total = 0;
        NoiseSpec[] specs = plan.specs();
        if (specs != null && slot < specs.length && specs[slot] != null) {
            total += specs[slot].totalActiveOctaves();
        }
        BlendedNoiseSpec[] blendedSpecs = plan.blendedSpecs();
        if (blendedSpecs != null && slot < blendedSpecs.length) {
            total += countActiveOctaves(blendedSpecs[slot]);
        }
        return total;
    }

    private static int countActiveOctaves(BlendedNoiseSpec spec) {
        if (spec == null) {
            return 0;
        }
        return countNonNull(spec.mainOctaves())
                + countNonNull(spec.minLimitOctaves())
                + countNonNull(spec.maxLimitOctaves());
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

    private static int countActiveOctaves(NoiseSpec[] specs, BlendedNoiseSpec[] blendedSpecs) {
        int total = countActiveOctaves(specs);
        if (blendedSpecs != null) {
            for (BlendedNoiseSpec spec : blendedSpecs) {
                total += countActiveOctaves(spec);
            }
        }
        return total;
    }

    private static int countNonNull(Object[] values) {
        int total = 0;
        if (values != null) {
            for (Object value : values) {
                if (value != null) {
                    total++;
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
                        + ", finalDensityHybrid=" + DfcOpenClConfig.finalDensityHybridEnabled()
                        + ", finalDensityHybridBroken=" + DfcOpenClRuntime.finalDensityHybridBroken()
                        + ", worldgenBridge=" + DfcOpenClConfig.worldgenBridgeEnabled()
                        + ", slabMinElements=" + DfcOpenClConfig.slabVmMinElements()
                        + ", hybridMinSlotValues=" + DfcOpenClConfig.finalDensityHybridMinSlotValues()
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
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL finalDensity hybrid: calls=" + stats.hybridCalls()
                        + ", attempts=" + stats.hybridAttempts()
                        + ", succeeded=" + stats.hybridSucceeded()
                        + ", failed=" + stats.hybridFailed()
                        + ", skipped={disabled=" + stats.hybridSkippedDisabled()
                        + ", unavailable=" + stats.hybridSkippedUnavailable()
                        + ", broken=" + stats.hybridSkippedBroken()
                        + ", invalid=" + stats.hybridSkippedInvalid()
                        + ", noPlan=" + stats.hybridSkippedNoPlan()
                        + ", tooSmall=" + stats.hybridSkippedTooSmall()
                        + ", noWaves=" + stats.hybridSkippedNoWaves()
                        + "}"
                        + (stats.hybridLastSkip() == null || stats.hybridLastSkip().isBlank()
                        ? ""
                        : ", lastSkip=" + stats.hybridLastSkip())),
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
