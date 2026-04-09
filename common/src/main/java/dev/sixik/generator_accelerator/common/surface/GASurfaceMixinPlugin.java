package dev.sixik.generator_accelerator.common.surface;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;

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
    }
}
