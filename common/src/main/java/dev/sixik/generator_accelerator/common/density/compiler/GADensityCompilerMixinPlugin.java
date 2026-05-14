package dev.sixik.generator_accelerator.common.density.compiler;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GADensityCompilerMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableDensityCompilerPatch;
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("",
                "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSampler",
                "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSampler1",
                "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCache2D",
                "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCacheOnce",
                "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCellCache",
                "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerDensityInterpolator",
                "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerFlatCache",
                "com.ishland.c2me.opts.dfc.mixin.MixinDFTBinaryOperation",
                "com.ishland.c2me.opts.dfc.mixin.MixinDFTWrapping",
                "com.ishland.c2me.opts.dfc.mixin.MixinNoiseConfig",
                "com.ishland.c2me.opts.dfc.mixin.MixinSplineImplementation"
        ));
    }
}
