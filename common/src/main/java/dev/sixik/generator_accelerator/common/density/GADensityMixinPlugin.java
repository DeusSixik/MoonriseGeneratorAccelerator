package dev.sixik.generator_accelerator.common.density;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GADensityMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableDensityPatch;
    }

    @Override
    public void onLoad(String s) {

    }
}
