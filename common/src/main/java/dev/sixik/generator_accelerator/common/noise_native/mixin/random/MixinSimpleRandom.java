package dev.sixik.generator_accelerator.common.noise_native.mixin.random;

import ca.spottedleaf.moonrise.common.util.SimpleRandom;
import dev.sixik.generator_accelerator.common.noise_native.RandomSeedGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SimpleRandom.class)
public class MixinSimpleRandom implements RandomSeedGetter {
    @Shadow
    private long value;

    @Override
    public long bts$getSeed() {
        return value;
    }
}
