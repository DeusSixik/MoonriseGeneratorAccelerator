package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.diagnostics.GADiagnostics;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;

public final class GeneratorAccelerator {
    private static final String LOGGER_NAME = "Generator Accelerator";
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);
    public static final Logger LOGGER_DEBUG = LoggerFactory.getLogger( LOGGER_NAME + " Debug");
    public static final String C2ME_MOD = "com.ishland.c2me.base.C2MEBaseMod";

    public static ForkJoinPool CUSTOM_POOL;
    public static Platform platform = null;

    public static void init(Platform platform, boolean isDev) {
        GeneratorAccelerator.platform = platform;
        GADiagnostics.onModInit();
        GAScheduler.init(isDev);
        CUSTOM_POOL = null;
    }

    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE
    }
}
