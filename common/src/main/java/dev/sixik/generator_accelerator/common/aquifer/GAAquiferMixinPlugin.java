package dev.sixik.generator_accelerator.common.aquifer;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAAquiferMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableAquiferPatch;
    }

    @Override
    public void onLoad(String s) {

    }
}
