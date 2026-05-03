package dev.sixik.generator_accelerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeneratorAccelerator {
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Platform platform = null;

    public static void init(Platform platform) {
        GeneratorAccelerator.platform = platform;
    }

    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE;

        public boolean isForge() {
            return this == FORGE || this == NEOFORGE;
        }
    }
}
