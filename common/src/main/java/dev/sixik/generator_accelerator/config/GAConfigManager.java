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
                return Optional.empty();
            }

            loadIsolatedConfig();
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

            System.out.println("Game directory: " + gameDir);
            System.out.println("Config directory: " + gameDir.resolve("config"));


            BaseShadowConfig<GAConfig> wrapper = SCYamlConfig.Builder.builder(GAConfig.class)
                    .defaults(new GAConfig())
                    .modId(GeneratorAccelerator.MOD_ID)
                    .side(ConfigSide.COMMON)
                    .path(gameDir.resolve("config"))
                    .build();

            return new Object[]{wrapper, wrapper.getCurrentConfig()};
        }
    }
}
