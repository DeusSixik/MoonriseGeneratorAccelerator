package dev.sixik.generator_accelerator.common.density.compiler;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GADensityCompilerMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableDensityCompilerPatch;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }
}
