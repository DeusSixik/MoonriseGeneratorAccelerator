package dev.sixik.generator_accelerator.common.blender;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GABlenderMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableBlenderPatch;
    }

    @Override
    public void onLoad(String s) {

    }
}
