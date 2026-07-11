package dev.sixik.generator_accelerator.common.aquifer;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAAquiferMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableAquiferPatch;
    }

    @Override
    public void onLoad(String s) {
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param(
                "",
                "com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer.MixinAquiferSamplerImpl"
        ));
    }
}
