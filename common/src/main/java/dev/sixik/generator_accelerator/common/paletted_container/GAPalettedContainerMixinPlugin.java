package dev.sixik.generator_accelerator.common.paletted_container;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

import java.util.Arrays;

public class GAPalettedContainerMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enablePalettedContainerPatch;
    }

    @Override
    public void onLoad(String s) {

    }
}
