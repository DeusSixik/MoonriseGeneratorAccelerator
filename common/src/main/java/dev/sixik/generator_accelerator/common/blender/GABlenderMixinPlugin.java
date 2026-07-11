package dev.sixik.generator_accelerator.common.blender;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import dev.sixik.generator_accelerator.mixins.GACoreMixinPlugin;

public class GABlenderMixinPlugin extends GAMixinPlugin {
    private static final String C2ME_BLENDER_COMPAT =
            "dev.sixik.generator_accelerator.common.blender.mixin.compats.c2me.c2me$BlenderMixin";

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableBlenderPatch;
    }

    @Override
    public void onLoad(String s) {
        this.create(GeneratorAccelerator.C2ME_MOD,
                new MixinApplier.Param(
                        C2ME_BLENDER_COMPAT,
                        "com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization.MixinBlender"
                )
        );
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (C2ME_BLENDER_COMPAT.equals(mixinClassName)
                && GAConfigManager.getConfigOrLoad()
                .map(GACoreMixinPlugin::shouldOwnC2meChunkSystem)
                .orElse(false)) {
            return false;
        }
        return super.shouldApplyMixin(targetClassName, mixinClassName);
    }
}
