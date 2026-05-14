package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GACoreMixinPlugin extends GAMixinPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // C2ME's worldgen-general random redirects assume vanilla LegacyRandomSource allocations.
        // GA rewrites the same random hot paths, so cancel the whole C2ME submodule's mixin set.
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("",
                "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinAtomicSimpleRandomFactory",
                "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandom",
                "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandomStatic"
        ));
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }
}
