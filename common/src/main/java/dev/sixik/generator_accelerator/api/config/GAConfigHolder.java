package dev.sixik.generator_accelerator.api.config;

import ca.spottedleaf.yamlconfig.adapter.TypeAdapterRegistry;
import ca.spottedleaf.yamlconfig.config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;

public class GAConfigHolder {

    private static final int CURRENT_CONFIG_VERSION = 12;

    private static final Logger LOGGER = LoggerFactory.getLogger(GAConfigHolder.class);
    private static boolean initialized = false;

    private static final File CONFIG_FILE = Paths.get(".")
            .resolve("config").resolve("generator_accelerator.yml").toFile();
    private static final TypeAdapterRegistry CONFIG_ADAPTER = new TypeAdapterRegistry();
    private static final YamlConfig<GAConfig> CONFIG;

    static {
        try {
            CONFIG = new YamlConfig<>(GAConfig.class, new GAConfig(), CONFIG_ADAPTER);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private GAConfigHolder() { }

    public static YamlConfig<GAConfig> getConfigRaw() {
        if(!initialized && reloadConfig()) {
            initialized = true;
        }

        return CONFIG;
    }

    public static GAConfig getConfig() {
        return getConfigRaw().config;
    }

    public static boolean reloadConfig() {
        synchronized (CONFIG) {
            if(CONFIG_FILE.exists()) {
                try {
                    CONFIG.load(CONFIG_FILE);
                } catch (Exception e) {
                    LOGGER.error("Failed to load config, using defaults", e);
                    return false;
                }
            }

            CONFIG.callInitialisers();
            migrateConfig(CONFIG.config);
            return saveConfig();
        }
    }

    private static void migrateConfig(GAConfig config) {
        if (config == null) {
            return;
        }

        if (config.version < 2 && config.dfc != null && config.dfc.splineLinearSearchMaxPoints == 4) {
            config.dfc.splineLinearSearchMaxPoints = 3;
            LOGGER.info("Migrated DFC splineLinearSearchMaxPoints from legacy default 4 to 3.");
        }

        if (config.version < 4 && config.dfc != null && !config.dfc.splineSegmentLut) {
            config.dfc.splineSegmentLut = true;
            LOGGER.info("Migrated DFC splineSegmentLut from legacy default false to true.");
        }

        if (config.version < 5 && config.dfc != null && config.dfc.splineSegmentLut) {
            config.dfc.splineSegmentLut = false;
            LOGGER.info("Migrated DFC splineSegmentLut back to false after runtime regression findings.");
        }

        if (config.version < 6 && config.dfc != null && config.dfc.randomStateCompileMax == 1) {
            config.dfc.randomStateCompileMax = 0;
            LOGGER.info("Migrated DFC randomStateCompileMax from legacy eager default 1 to safe default 0.");
        }

        if (config.dfc == null) {
            config.dfc = new GAConfig.DfcDebugConfig();
        }
        if (config.biomeClimate == null) {
            config.biomeClimate = new GAConfig.BiomeClimateConfig();
        }

        config.version = CURRENT_CONFIG_VERSION;
    }

    public static boolean saveConfig() {
        synchronized (CONFIG) {
            try {
                CONFIG.save(CONFIG_FILE);
                return true;
            } catch (Exception e) {
                LOGGER.error("Failed to save config", e);
                return false;
            }
        }
    }

}
