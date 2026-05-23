package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.diagnostics.GADiagnostics;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import net.minecraft.world.level.ChunkPos;
import net.sixik.ga_profiler.Profiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;

public final class GeneratorAccelerator {
    private static final String LOGGER_NAME = "Generator Accelerator";
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);
    public static final Logger LOGGER_DEBUG = LoggerFactory.getLogger( LOGGER_NAME + " Debug");
    public static final String C2ME_MOD = "com.ishland.c2me.base.C2MEBaseMod";

    public static ForkJoinPool CUSTOM_POOL;
    public static Platform platform = null;

    public static Path gameFolder;

    public static ConcurrentMap<ChunkPos, Long> chunkGenerationTimes = new ConcurrentHashMap<>();

    public static void init(Platform platform, boolean isDev, Path gameFolder) {
        GeneratorAccelerator.platform = platform;
        GeneratorAccelerator.gameFolder = gameFolder;
        GADiagnostics.onModInit();
        GAScheduler.init(isDev);
        CUSTOM_POOL = Boolean.parseBoolean(System.getProperty("ga.scheduler.overrideNoiseExecutor", "true")) ? GAScheduler.noisePool() : null;


        Profiler.setAllocationProfilingEnabled(false);
        CHUNK_GENERATION = Profiler.register(
                "generation.chunk",
                "Generation of a chunk",
                0
        );
    }

    public static Profiler.Section CHUNK_GENERATION;

    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE
    }
}
