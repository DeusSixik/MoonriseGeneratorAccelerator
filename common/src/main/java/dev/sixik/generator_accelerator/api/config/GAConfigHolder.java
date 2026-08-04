package dev.sixik.generator_accelerator.api.config;

public final class GAConfigHolder {
    private static final GAConfig CONFIG = new GAConfig();

    private GAConfigHolder() {
    }

    public static GAConfig getConfig() {
        CONFIG.refreshFromSystemProperties();
        return CONFIG;
    }

    public static boolean reloadConfig() {
        getConfig();
        return true;
    }
}
