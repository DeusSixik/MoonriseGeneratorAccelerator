package dev.sixik.generator_accelerator.common.density;

import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.common.density.compiler.GADensityCompilerMixinPlugin;

public class DensityGAMixinPlugin extends GADensityCompilerMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableDensityCompilerPatch;
    }
}
