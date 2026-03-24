package dev.sixik.generator_accelerator.common.noise_native.mixin.random;

import dev.sixik.generator_accelerator.common.noise_native.RandomSeedGetter;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SingleThreadedRandomSource.class)
public class MixinSingleThreadedRandomSource implements RandomSeedGetter {
    @Shadow
    private long seed;

    @Override
    public long bts$getSeed() {
        return seed;
    }
}
