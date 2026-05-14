package dev.sixik.generator_accelerator.common.blender;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GABlenderMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableBlenderPatch;
    }

    @Override
    public void onLoad(String s) {
        this.create(GeneratorAccelerator.C2ME_MOD,
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.blender.mixin.compats.c2me.c2me$BlenderMixin",
                        "com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization.MixinBlender"
                )
        );
    }
}
