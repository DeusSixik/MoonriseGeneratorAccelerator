package dev.sixik.generator_accelerator.common.noise;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GANoiseMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableNoisePatch;
    }

    @Override
    public void onLoad(String s) {

    }
}
