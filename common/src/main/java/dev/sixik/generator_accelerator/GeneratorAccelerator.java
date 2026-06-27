package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import dev.sixik.generator_accelerator.common.density.compiler.DfcConfigBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class GeneratorAccelerator {
    private static final String LOGGER_NAME = "Generator Accelerator";
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private static Platform platform = null;
    private static Path gameFolder;
    private static boolean devMode;
    private static final boolean useProfiler = false;

    public static void init(Platform platform, boolean isDev, Path gameFolder) {
        GeneratorAccelerator.platform = platform;
        GeneratorAccelerator.gameFolder = gameFolder;
        GeneratorAccelerator.devMode = isDev;
        DfcConfigBridge.applySystemPropertiesFromConfig();
    }

    public static boolean isUseProfiler() {
        return useProfiler;
    }

    public static Path getGameFolder() {
        return gameFolder;
    }

    public static Platform getPlatform() {
        return platform;
    }

    public static boolean isDevMode() {
        return devMode;
    }

    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE
    }
}
