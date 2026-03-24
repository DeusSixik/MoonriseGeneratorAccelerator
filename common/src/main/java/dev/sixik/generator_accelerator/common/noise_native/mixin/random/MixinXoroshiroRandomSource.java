package dev.sixik.generator_accelerator.common.noise_native.mixin.random;

import dev.sixik.generator_accelerator.common.noise_native.RandomSeedGetter;
import dev.sixik.generator_accelerator.math.SeedReverser;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(XoroshiroRandomSource.class)
public class MixinXoroshiroRandomSource implements RandomSeedGetter {

    @Shadow
    public Xoroshiro128PlusPlus randomNumberGenerator;

    @Unique
    private long bts$seed;

    @Inject(method = "<init>*", at = @At("RETURN"))
    public void bts$init(CallbackInfo ci) {
        RandomSupport.Seed128bit bits = new RandomSupport.Seed128bit(randomNumberGenerator.seedLo, randomNumberGenerator.seedHi);
        Long recovered = SeedReverser.tryGetOriginalSeed(bits);
        bts$seed = Objects.requireNonNullElseGet(recovered, () -> randomNumberGenerator.seedLo ^ randomNumberGenerator.seedHi);
    }

    @Override
    public long bts$getSeed() {
        return bts$seed;
    }

    @Override
    public long bts$getSeedHi() {
        return randomNumberGenerator.seedHi;
    }

    @Override
    public long bts$getSeedLo() {
        return randomNumberGenerator.seedLo;
    }
}
