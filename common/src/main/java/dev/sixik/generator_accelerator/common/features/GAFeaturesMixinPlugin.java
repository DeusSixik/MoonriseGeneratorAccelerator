package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;

public class GAFeaturesMixinPlugin extends GAMixinPlugin {

    @Override
    public void onLoad(String s) {
        create("org.confluence.mod.Confluence", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.confluence.Confluence$PlacedFeatureMixin$fix",
                "org.confluence.mod.mixin.level.PlacedFeatureMixin"
        ));
    }
}
