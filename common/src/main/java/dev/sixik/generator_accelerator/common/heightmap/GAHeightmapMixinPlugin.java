package dev.sixik.generator_accelerator.common.heightmap;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAHeightmapMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableHeightmapPatch;
    }

    @Override
    public void onLoad(String s) {

    }
}
