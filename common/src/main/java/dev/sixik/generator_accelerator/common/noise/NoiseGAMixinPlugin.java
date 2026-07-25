package dev.sixik.generator_accelerator.common.noise;

import dev.sixik.generator_accelerator.api.config.GAConfig;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.annotation.AutoMixinPlugin;

@AutoMixinPlugin
public class NoiseGAMixinPlugin extends GAMixinPlugin {

    private static final String MIXIN_BASE = "dev.sixik.generator_accelerator.common.noise.mixin.";
    private static final String SYNTH_BASE = MIXIN_BASE + "synth.";

    @Override
    protected void load(String mixinPackage) {
        addPropertyGate(SYNTH_BASE + "MixinBlendedNoise$optimization_math", "blendedNoise");
        addPropertyGate(SYNTH_BASE + "MixinImprovedNoise$optimization_through_flat_math", "improvedNoise");
        addPropertyGate(SYNTH_BASE + "MixinPerlinNoise$optimize_octave_accumulation", "perlinNoise");
        addPropertyGate(SYNTH_BASE + "MixinSimplexNoise$optimization_through_flat_math", "simplexNoise");
        addPropertyGate(MIXIN_BASE + "MixinNoiseChunk$optimization_math", "noiseChunk");
        addPropertyGate(MIXIN_BASE + "MixinNoiseChunk$Interpolator$optimization_math", "noiseChunkInterpolator");
        addPropertyGate(MIXIN_BASE + "MixinNoiseChunk$FlatCache$optimization_math", "noiseChunkFlatCache");
        addPropertyGate(MIXIN_BASE + "MixinNoiseChunk$Cache2D$optimization_math", "noiseChunkCache2D");
    }

    private void addPropertyGate(String mixinClass, String name) {
        addMixinToConfig(mixinClass, ignored -> Boolean.parseBoolean(
                System.getProperty("ga.noise.mixin." + name, "true")));
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableNoisePath;
    }
}
