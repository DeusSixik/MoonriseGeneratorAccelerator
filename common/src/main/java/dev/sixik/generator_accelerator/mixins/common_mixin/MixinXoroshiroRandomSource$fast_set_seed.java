package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = XoroshiroRandomSource.class, priority = 999)
public abstract class MixinXoroshiroRandomSource$fast_set_seed {

    @Shadow
    public Xoroshiro128PlusPlus randomNumberGenerator;

    @Shadow
    private MarsagliaPolarGaussian gaussianSource;

    /**
     * @author Sixik
     * @reason Preserve vanilla seed mixing while avoiding Seed128bit and Xoroshiro128PlusPlus allocation.
     */
    @Overwrite
    public void setSeed(long seed) {
        long seedLo = seed ^ 7640891576956012809L;
        long seedHi = seedLo + -7046029254386353131L;
        seedLo = RandomSupport.mixStafford13(seedLo);
        seedHi = RandomSupport.mixStafford13(seedHi);
        if ((seedLo | seedHi) == 0L) {
            seedLo = -7046029254386353131L;
            seedHi = 7640891576956012809L;
        }

        this.randomNumberGenerator.seedLo = seedLo;
        this.randomNumberGenerator.seedHi = seedHi;
        this.gaussianSource.reset();
    }
}
