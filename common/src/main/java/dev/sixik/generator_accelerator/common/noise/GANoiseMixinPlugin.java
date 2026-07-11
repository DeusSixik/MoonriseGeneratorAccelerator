package dev.sixik.generator_accelerator.common.noise;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GANoiseMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableNoisePatch;
    }

    @Override
    public void onLoad(String s) {
        this.create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param(
                "",
                "com.ishland.c2me.opts.math.mixin.MixinChunkNoiseSampler",
                "com.ishland.c2me.opts.math.mixin.MixinOctavePerlinNoiseSampler",
                "com.ishland.c2me.opts.math.mixin.MixinPerlinNoiseSampler",
                "com.ishland.c2me.opts.natives_math.mixin.MixinDoublePerlinNoiseSampler",
                "com.ishland.c2me.opts.natives_math.mixin.MixinInterpolatedNoiseSampler"
        ));
    }
}
