package dev.sixik.generator_accelerator.common.noise_native.mixin.random;

import dev.sixik.generator_accelerator.common.noise_native.RandomSeedGetter;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(LegacyRandomSource.class)
public class MixinLegacyRandomSource implements RandomSeedGetter {
    @Shadow
    @Final
    private AtomicLong seed;

    @Override
    public long bts$getSeed() {
        return seed.get();
    }
}
