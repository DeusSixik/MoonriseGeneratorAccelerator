package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.synth;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.IntStream;

@Mixin(PerlinNoise.class)
public abstract class MixinPerlinNoise {

    @Shadow
    @Final
    private ImprovedNoise[] noiseLevels;
    @Shadow
    @Final
    private DoubleList amplitudes;

    @Shadow
    @Final
    private double lowestFreqInputFactor;
    @Shadow
    @Final
    private double lowestFreqValueFactor;

    @Unique
    private int canvas$octaveSamplersCount;

    @Unique
    private double[] canvas$amplitudesArray;

    @Inject(method = "<init>", at = @At("TAIL"))
    protected void bts$createLegacyForBlendedNoise(CallbackInfo ci) {
        canvas$octaveSamplersCount = noiseLevels.length;
        canvas$amplitudesArray = amplitudes.toDoubleArray();
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        double d = 0.0;
        double e = this.lowestFreqInputFactor;
        double f = this.lowestFreqValueFactor;
        for (int i = 0; i < this.canvas$octaveSamplersCount; ++i) {
            ImprovedNoise perlinNoiseSampler = this.noiseLevels[i];
            if (perlinNoiseSampler != null) {
                @SuppressWarnings("deprecation")
                double g = perlinNoiseSampler.noise(
                        PerlinNoise.wrap(x * e), PerlinNoise.wrap(y * e), PerlinNoise.wrap(z * e), 0.0, 0.0
                );
                d += this.canvas$amplitudesArray[i] * g * f;
            }
            e *= 2.0;
            f /= 2.0;
        }
        return d;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public static double wrap(double value) {
        return value - Math.floor(value / 3.3554432E7 + 0.5) * 3.3554432E7;
    }
}
