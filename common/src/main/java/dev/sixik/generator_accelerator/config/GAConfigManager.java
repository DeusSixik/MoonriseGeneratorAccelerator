package dev.sixik.generator_accelerator.config;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.shadowking21.shadowconfig.config.BaseShadowConfig;
import net.shadowking21.shadowconfig.config.ConfigSide;
import net.shadowking21.shadowconfig.config.exstensions.yaml.SCYamlConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class GAConfigManager {

    private static Object config;
    private static Object configWrapper;

    public static boolean isConfigAvailable() {
        try {
            Class.forName("net.shadowking21.shadowconfig.ShadowConfig", false, GAConfigManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static Optional<GAConfig> getConfigOrLoad() {
        if(config == null) {
            if(!isConfigAvailable()) {
                config = new GAConfig();
            } else loadIsolatedConfig();
            applySystemOverrides((GAConfig) config);
        }
        return Optional.of((GAConfig) config);
    }

    public static Optional<Object> getConfigWrapper() {
        return Optional.ofNullable(configWrapper);
    }

    private static void loadIsolatedConfig() {
        Object[] result = ShadowConfigImpl.init();
        configWrapper = result[0];
        config = result[1];
    }

    private static class ShadowConfigImpl {

        static Object[] init() {
            Path gameDir = Paths.get(System.getProperty("user.dir"));
            BaseShadowConfig<GAConfig> wrapper = SCYamlConfig.Builder.builder(GAConfig.class)
                    .defaults(new GAConfig())
                    .modId(GeneratorAccelerator.MOD_ID)
                    .side(ConfigSide.COMMON)
                    .path(gameDir.resolve("config"))
                    .build();

            return new Object[]{wrapper, wrapper.read()};
        }
    }

    private static void applySystemOverrides(GAConfig config) {
        if (Boolean.getBoolean("ga.benchmark.disableAllPatches")) {
            setAllPatches(config, false);
        }
        if (Boolean.getBoolean("ga.benchmark.featuresOnly")) {
            setAllPatches(config, false);
            config.enableFeaturesPatch = true;
        }

        config.enableAquiferPatch = boolOverride("ga.config.enableAquiferPatch", config.enableAquiferPatch);
        config.enableBeardifierPatch = boolOverride("ga.config.enableBeardifierPatch", config.enableBeardifierPatch);
        config.enableBiomePatch = boolOverride("ga.config.enableBiomePatch", config.enableBiomePatch);
        config.enableBlenderPatch = boolOverride("ga.config.enableBlenderPatch", config.enableBlenderPatch);
        config.enableDensityCompilerPatch = boolOverride("ga.config.enableDensityCompilerPatch", config.enableDensityCompilerPatch);
        config.enableFeaturesPatch = boolOverride("ga.config.enableFeaturesPatch", config.enableFeaturesPatch);
        config.enableFlatBlockStructurePatch = boolOverride("ga.config.enableFlatBlockStructurePatch", config.enableFlatBlockStructurePatch);
        config.enableHeightmapPatch = boolOverride("ga.config.enableHeightmapPatch", config.enableHeightmapPatch);
        config.enableNoisePatch = boolOverride("ga.config.enableNoisePatch", config.enableNoisePatch);
        config.enableNoiseNativePatch = boolOverride("ga.config.enableNoiseNativePatch", config.enableNoiseNativePatch);
        config.enablePalettedContainerPatch = boolOverride("ga.config.enablePalettedContainerPatch", config.enablePalettedContainerPatch);
        config.enableStructuresPatch = boolOverride("ga.config.enableStructuresPatch", config.enableStructuresPatch);
        config.enableSurfacePatch = boolOverride("ga.config.enableSurfacePatch", config.enableSurfacePatch);

        config.schedulerNoiseWorkers = intOverride("ga.config.schedulerNoiseWorkers", config.schedulerNoiseWorkers);
        config.schedulerCompileWorkers = intOverride("ga.config.schedulerCompileWorkers", config.schedulerCompileWorkers);
        config.schedulerWorkspaceWorkers = intOverride("ga.config.schedulerWorkspaceWorkers", config.schedulerWorkspaceWorkers);
        config.schedulerCommitWorkers = intOverride("ga.config.schedulerCommitWorkers", config.schedulerCommitWorkers);
        config.schedulerMaxQueuedTasks = intOverride("ga.config.schedulerMaxQueuedTasks", config.schedulerMaxQueuedTasks);
        config.schedulerCpuTarget = doubleOverride("ga.config.schedulerCpuTarget", config.schedulerCpuTarget);
        config.maxInFlightWorkspaces = intOverride("ga.config.maxInFlightWorkspaces", config.maxInFlightWorkspaces);
        config.workspaceMaxRetainedBytes = longOverride("ga.config.workspaceMaxRetainedBytes", config.workspaceMaxRetainedBytes);
    }

    private static boolean boolOverride(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int intOverride(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longOverride(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleOverride(String property, double fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void setAllPatches(GAConfig config, boolean enabled) {
        config.enableAquiferPatch = enabled;
        config.enableBeardifierPatch = enabled;
        config.enableBiomePatch = enabled;
        config.enableBlenderPatch = enabled;
        config.enableDensityCompilerPatch = enabled;
        config.enableFeaturesPatch = enabled;
        config.enableFlatBlockStructurePatch = enabled;
        config.enableHeightmapPatch = enabled;
        config.enableNoisePatch = enabled;
        config.enableNoiseNativePatch = enabled;
        config.enablePalettedContainerPatch = enabled;
        config.enableStructuresPatch = enabled;
        config.enableSurfacePatch = enabled;
    }
}
