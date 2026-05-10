package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.diagnostics.GADiagnostics;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;

public final class GeneratorAccelerator {
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ForkJoinPool CUSTOM_POOL;
    public static Platform platform = null;

    public static void init(Platform platform, boolean isDev) {
        GeneratorAccelerator.platform = platform;
        GADiagnostics.onModInit();
        GAScheduler.init(isDev);
        CUSTOM_POOL = GAScheduler.noisePool();
    }

    public static void tryLoadNatives() {
        if(GeneratorAcceleratorNatives.isLoaded()) return;
        GeneratorAcceleratorNatives.initialize();
    }


    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE
    }
}
