package dev.sixik.generator_accelerator_dod.impl;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GADodMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }

    @Override
    public void onLoad(String s) {

    }
}
