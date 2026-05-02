package dev.sixik.generator_accelerator.common.noise_native.mixin;

import dev.sixik.generator_accelerator.common.noise_native.NativePtrGetter;
import dev.sixik.generator_accelerator.common.noise_native.RandomSeedGetter;
import dev.sixik.generator_accelerator.math.c3.NativeNormalNoise;
import dev.sixik.generator_accelerator.math.c3.NativeRandom;
import dev.sixik.generator_accelerator.math.c3.cleaner.NativeObjectCleaner;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.ref.Cleaner;

@Mixin(NormalNoise.class)
public class MixinNormalNoise$redirect_to_native implements NativePtrGetter {

    @Unique
    private static final Cleaner BTS$CLEANER = Cleaner.create();

    @Unique
    private long bts$ptr = 0;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(RandomSource randomSource, NormalNoise.NoiseParameters noiseParameters, boolean bl, CallbackInfo ci) {

        final boolean horo = randomSource instanceof XoroshiroRandomSource;

        // Get original seed from random
        final long bts$seed_1;
        long bts$seed_2 = 0;
        if(horo) {
            XoroshiroRandomSource randomSource1 = (XoroshiroRandomSource) randomSource;
            bts$seed_1 = randomSource1.randomNumberGenerator.seedLo;
            bts$seed_2 = randomSource1.randomNumberGenerator.seedHi;
        } else {
            bts$seed_1 = ((RandomSeedGetter)randomSource).bts$getSeed();
        }

        // Create Native Random version
        final long randomPtr = horo
                ? NativeRandom.createXoroshiro(bts$seed_1, bts$seed_2)
                : NativeRandom.create(bts$seed_1);

        // Create Native NormalNoise version
        this.bts$ptr = NativeNormalNoise.create(randomPtr, noiseParameters.firstOctave(), noiseParameters.amplitudes().toDoubleArray());


        if (this.bts$ptr != 0) {
            // Register cleaner for unload allocated structure from memory
            BTS$CLEANER.register(this, new NativeObjectCleaner.NativeState(this.bts$ptr));
        }
    }

    /**
     * @author Sixik
     * @reason Redirect to native method
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        if(y == 0) {
            return NativeNormalNoise.getValue2D(bts$ptr, x, z);
        }

        return NativeNormalNoise.getValue(bts$ptr, x, y, z);
    }

    @Override
    public long bts$getPtr() {
        return bts$ptr;
    }
}
