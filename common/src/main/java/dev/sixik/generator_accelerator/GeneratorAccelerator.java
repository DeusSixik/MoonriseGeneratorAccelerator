package dev.sixik.generator_accelerator;

import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GeneratorAccelerator {
    private static final String LOGGER_NAME = "Generator Accelerator";
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    public static Platform platform = null;
    public static Path gameFolder;

    public static ConcurrentMap<ChunkPos, Long> chunkGenerationTimes = new ConcurrentHashMap<>();

    public static void init(Platform platform, boolean isDev, Path gameFolder) {
        GeneratorAccelerator.platform = platform;
        GeneratorAccelerator.gameFolder = gameFolder;
    }

    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE
    }
}
