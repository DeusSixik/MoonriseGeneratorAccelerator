package dev.sixik.generator_accelerator.common.surface;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GASurfaceMixinPlugin extends GAMixinPlugin {

    @Override
    public void onLoad(String s) {
        create("net.hibiscus.naturespirit.NatureSpirit", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.surface.mixin.compats.natures_spirit.NaturesSpirit$SurfaceBuilderMixin$fix_compat",
                "net.hibiscus.naturespirit.mixin.SurfaceBuilderMixin"
        ));

        create("net.potionstudios.biomeswevegone.BiomesWeveGone", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.surface.mixin.compats.biomeswevegone.BiomesWeveGone$SurfaceBuilder$fix_compat",
                "net.potionstudios.biomeswevegone.mixin.SurfaceSystemMixin"
        ));

        create("com.terraformersmc.biolith.impl.Biolith",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.surface.mixin.compats.biolith.Mixin$Biolith$SurfaceSystem",
                        "com.terraformersmc.biolith.impl.mixin.MixinSurfaceBuilder"
                )
        );

        this.create(GeneratorAccelerator.C2ME_MOD,
                new MixinApplier.Param("",
                        "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRulesSequenceMaterialRule",
                        "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRulesSequenceBlockStateRule",
                        "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRuleContext"
                ));

    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableSurfacePatch;
    }
}
