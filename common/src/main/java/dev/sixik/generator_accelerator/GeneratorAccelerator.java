package dev.sixik.generator_accelerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;

public final class GeneratorAccelerator {
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ForkJoinPool CUSTOM_POOL = new ForkJoinPool(Math.min(0x7fff, Runtime.getRuntime().availableProcessors()));

    public static Platform platform = null;

    public static void init(Platform platform) {
        GeneratorAccelerator.platform = platform;
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
