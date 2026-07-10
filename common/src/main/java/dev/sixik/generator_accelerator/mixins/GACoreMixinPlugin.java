package dev.sixik.generator_accelerator.mixins;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GACoreMixinPlugin extends GAMixinPlugin {
    private static final String LITHIUM = "net.caffeinemc.mods.lithium.common.LithiumMod";
    private static final String LITHIUM_FLOWING_FLUID = "net.caffeinemc.mods.lithium.mixin.block.fluid.flow.FlowingFluidMixin";
    private static final String WORLD_CARVER_MIXIN = "dev.sixik.generator_accelerator.mixins.common_mixin.MixinWorldCarver";
    private static final MixinApplier REGIONS_UNEXPLORED = new MixinApplier(
            "net.regions_unexplored.mixin.WorldCarverMixin",
            new MixinApplier.Param[0]
    );

    @Override
    public void onLoad(String mixinPackage) {
        // C2ME's worldgen-general random redirects assume vanilla LegacyRandomSource allocations.
        // GA rewrites the same random hot paths, so cancel the whole C2ME submodule's mixin set.
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("",
                "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinAtomicSimpleRandomFactory",
                "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandom",
                "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandomStatic"
        ));

        // GA overwrites FlowingFluid's spread/occlusion hot path. Lithium injects into
        // the same methods, so keep Lithium's unrelated patches and drop only this one.
        create(LITHIUM, new MixinApplier.Param("", LITHIUM_FLOWING_FLUID));
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (WORLD_CARVER_MIXIN.equals(mixinClassName) && REGIONS_UNEXPLORED.isModLoaded()) {
            return false;
        }

        return super.shouldApplyMixin(targetClassName, mixinClassName);
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }
}
