package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAFeaturesMixinPlugin extends GAMixinPlugin {

    @Override
    public void onLoad(String s) {
        GeneratorAccelerator.platform = isLoaded("net.fabricmc.api.ModInitializer") ? GeneratorAccelerator.Platform.FABRIC : GeneratorAccelerator.Platform.NEOFORGE;

        create("org.confluence.mod.Confluence",
                new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.confluence.Confluence$PlacedFeatureMixin$fix",
                "org.confluence.mod.mixin.level.PlacedFeatureMixin"
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.confluence.Confluence$SecretFlagPlacementMixin",
                        ""
                )
        );


        create("io.wispforest.owo.Owo", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.owo.Mixin$OWO$OreFeature",
                "io.wispforest.owo.mixin.Copenhagen"
        ));
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableFeaturesPatch;
    }

    private boolean isLoaded(String modClassPath) {
        if(modClassPath.isEmpty()) return true;

        try {
            Class.forName(modClassPath, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }
}
