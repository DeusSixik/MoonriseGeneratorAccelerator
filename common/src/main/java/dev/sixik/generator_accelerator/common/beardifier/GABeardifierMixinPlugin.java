package dev.sixik.generator_accelerator.common.beardifier;

import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GABeardifierMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableBeardifierPatch;
    }

    @Override
    public void onLoad(String s) {
        create("dev.worldgen.lithostitched.Lithostitched", new MixinApplier.Param(
                "",
                "dev.worldgen.lithostitched.mixin.common.BeardifierMixin"
        ));
    }
}
