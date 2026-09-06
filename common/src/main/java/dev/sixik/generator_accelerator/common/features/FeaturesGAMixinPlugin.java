package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator.api.config.GAConfig;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.annotation.AutoMixinPlugin;

@AutoMixinPlugin
public class FeaturesGAMixinPlugin extends GAMixinPlugin {

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }
}
