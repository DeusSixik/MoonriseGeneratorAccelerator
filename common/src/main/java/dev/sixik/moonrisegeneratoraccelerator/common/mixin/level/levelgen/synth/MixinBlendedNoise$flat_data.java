package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.synth;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlendedNoise.class)
public class MixinBlendedNoise$flat_data {

    @Shadow
    @Final
    private double xzMultiplier;
    @Shadow
    @Final
    private double yMultiplier;
    @Shadow
    @Final
    private double xzFactor;
    @Shadow
    @Final
    private double yFactor;
    @Shadow
    @Final
    private double smearScaleMultiplier;
    @Shadow
    @Final
    private PerlinNoise minLimitNoise;
    @Shadow
    @Final
    private PerlinNoise maxLimitNoise;
    @Shadow
    @Final
    private PerlinNoise mainNoise;

    @Unique
    private ImprovedNoise[] bts$mainNoises;
    @Unique
    private double[] bts$mainFreqs;
    @Unique
    private double[] bts$mainAmps;

    @Unique
    private ImprovedNoise[] bts$minNoises;
    @Unique
    private double[] bts$minFreqs;
    @Unique
    private double[] bts$minAmps;

    @Unique
    private ImprovedNoise[] bts$maxNoises;
    @Unique
    private double[] bts$maxFreqs;
    @Unique
    private double[] bts$maxAmps;

    @Unique
    private static final double WRAP_DIVISOR_INV = 1.0 / 3.3554432E7;

    @Inject(method = "<init>(Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;DDDDD)V", at = @At("RETURN"))
    private void onInit(PerlinNoise perlinNoise, PerlinNoise perlinNoise2, PerlinNoise perlinNoise3,
                        double d, double e, double f, double g, double h, CallbackInfo ci) {

        /*
            Preparing the Main Noise (8 octaves)
         */
        this.bts$flattenNoise(this.mainNoise, 8, false);

        /*
            Preparing the Min/Max Limit (16 octaves)
         */
        this.bts$flattenNoise(this.minLimitNoise, 16, true);
        this.bts$flattenNoise(this.maxLimitNoise, 16, true);
    }

    @Unique
    private void bts$flattenNoise(PerlinNoise source, int octaves, boolean isLimit) {

        /*
            Calculate the actual number of non-null octaves
         */
        int count = 0;
        double o = 1.0;
        for (int i = 0; i < octaves; i++) {
            if (source.getOctaveNoise(i) != null) count++;
            o /= 2.0;
        }

        ImprovedNoise[] noises = new ImprovedNoise[count];
        double[] freqs = new double[count];
        double[] amps = new double[count];

        int idx = 0;
        o = 1.0;
        for (int i = 0; i < octaves; i++) {
            ImprovedNoise noise = source.getOctaveNoise(i);
            if (noise != null) {
                noises[idx] = noise;
                freqs[idx] = o;

                /*
                    In the original, main is divided by o (noise / o) -> this is a multiplication by (1/o)
                    In the original, min/max are also divided into o
                 */
                amps[idx] = 1.0 / o;
                idx++;
            }
            o /= 2.0;
        }

        if (source == this.mainNoise) {
            this.bts$mainNoises = noises;
            this.bts$mainFreqs = freqs;
            this.bts$mainAmps = amps;
        } else if (source == this.minLimitNoise) {
            this.bts$minNoises = noises;
            this.bts$minFreqs = freqs;
            this.bts$minAmps = amps;
        } else {
            this.bts$maxNoises = noises;
            this.bts$maxFreqs = freqs;
            this.bts$maxAmps = amps;
        }
    }

    /**
     * @author Sixik
     * @reason Removing branching (if != null), inlining wrap, and using flat arrays.
     */
    @Overwrite
    public double compute(net.minecraft.world.level.levelgen.DensityFunction.FunctionContext ctx) {
        double blockX = (double) ctx.blockX() * this.xzMultiplier;
        double blockY = (double) ctx.blockY() * this.yMultiplier;
        double blockZ = (double) ctx.blockZ() * this.xzMultiplier;

        double g = blockX / this.xzFactor;
        double h = blockY / this.yFactor;
        double i = blockZ / this.xzFactor;

        double j = this.yMultiplier * this.smearScaleMultiplier;
        double k = j / this.yFactor;

        double[] bts$mainFreqs = this.bts$mainFreqs;
        double[] bts$mainAmps = this.bts$mainAmps;

        /*
            Main Noise
         */
        double n = 0.0;
        for (int idx = 0; idx < this.bts$mainNoises.length; ++idx) {
            double freq = bts$mainFreqs[idx];
            double inX = bts$fastWrap(g * freq);
            double inY = bts$fastWrap(h * freq);
            double inZ = bts$fastWrap(i * freq);

            n += this.bts$mainNoises[idx].noise(inX, inY, inZ, k * freq, h * freq) * bts$mainAmps[idx];
        }

        double q = (n / 10.0 + 1.0) / 2.0;

        boolean skipMin = q >= 1.0;
        boolean skipMax = q <= 0.0;

        double l = 0.0;
        double m = 0.0;

        double[] bts$minFreqs = this.bts$minFreqs;
        double[] bts$minAmps = this.bts$minAmps;

        /*
            Min Limit
         */
        if (!skipMin) {
            for (int idx = 0; idx < this.bts$minNoises.length; ++idx) {
                double freq = bts$minFreqs[idx];
                double inX = bts$fastWrap(blockX * freq);
                double inY = bts$fastWrap(blockY * freq); // In the original, 't' is used
                double inZ = bts$fastWrap(blockZ * freq);

                l += this.bts$minNoises[idx].noise(inX, inY, inZ, j * freq, blockY * freq) * bts$minAmps[idx];
            }
        }

        double[] bts$maxFreqs = this.bts$maxFreqs;
        double[] bts$maxAmps = this.bts$maxAmps;

        /*
            Max Limit
         */
        if (!skipMax) {
            for (int idx = 0; idx < this.bts$maxNoises.length; ++idx) {
                double freq = bts$maxFreqs[idx];
                double inX = bts$fastWrap(blockX * freq);
                double inY = bts$fastWrap(blockY * freq);
                double inZ = bts$fastWrap(blockZ * freq);

                m += this.bts$maxNoises[idx].noise(inX, inY, inZ, j * freq, blockY * freq) * bts$maxAmps[idx];
            }
        }


        /*
            In the original, divided by 512 and 128.
            We can replace division with multiplication by constants: * 0.001953125 and * 0.0078125
         */
        return Mth.clampedLerp(l * 0.001953125, m * 0.001953125, q) * 0.0078125;
    }

    @Unique
    private static double bts$fastWrap(double d) {
        return d - (double) Mth.lfloor(d * WRAP_DIVISOR_INV + 0.5) * 3.3554432E7;
    }
}
