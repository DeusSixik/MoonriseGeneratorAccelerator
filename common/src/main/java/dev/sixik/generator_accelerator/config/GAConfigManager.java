package dev.sixik.generator_accelerator.config;

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

            config = new GAConfig();
//            loadIsolatedConfig();
        }
        return Optional.of((GAConfig) config);
    }

    public static Optional<Object> getConfigWrapper() {
        return Optional.ofNullable(configWrapper);
    }

//    private static void loadIsolatedConfig() {
//        Object[] result = ShadowConfigImpl.init();
//        configWrapper = result[0];
//        config = result[1];
//    }

    private static class ShadowConfigImpl {

//        static Object[] init() {
//            Path gameDir = Paths.get(System.getProperty("user.dir"));
//            BaseShadowConfig<GAConfig> wrapper = SCYamlConfig.Builder.builder(GAConfig.class)
//                    .defaults(new GAConfig())
//                    .modId(GeneratorAccelerator.MOD_ID)
//                    .side(ConfigSide.COMMON)
//                    .path(gameDir.resolve("config"))
//                    .build();
//
//            return new Object[]{wrapper, wrapper.read()};
//        }
    }
}