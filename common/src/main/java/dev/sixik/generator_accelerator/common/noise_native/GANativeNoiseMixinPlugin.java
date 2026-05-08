package dev.sixik.generator_accelerator.common.noise_native;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GANativeNoiseMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return false;
    }

    @Override
    public void onLoad(String s) {

    }
}
