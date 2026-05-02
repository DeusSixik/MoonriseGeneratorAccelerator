package dev.sixik.generator_accelerator.common.biome;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GABiomeMixinPlugin extends GAMixinPlugin {

    @Override
    public void onLoad(String s) {
        create("terrablender.core.TerraBlender", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinParameterList$redirect_search",
                ""
        ));
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableBiomePatch;
    }
}
