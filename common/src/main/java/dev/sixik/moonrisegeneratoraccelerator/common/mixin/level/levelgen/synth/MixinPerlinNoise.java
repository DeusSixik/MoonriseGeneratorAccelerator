package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.synth;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.noise.ColumnNoiseFiller;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

@Mixin(PerlinNoise.class)
public abstract class MixinPerlinNoise implements ColumnNoiseFiller {

    @Unique
    private static final double WRAP_DOMAIN = 33554432.0;
    @Unique
    private static final double WRAP_RECRIPROCAL = 1.0 / WRAP_DOMAIN;

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

        final ImprovedNoise[] array = this.noiseLevels;
        final double[] amplitudesArray = this.canvas$amplitudesArray;

        for (int i = 0; i < this.canvas$octaveSamplersCount; ++i) {
            ImprovedNoise perlinNoiseSampler = array[i];
            if (perlinNoiseSampler != null) {
                @SuppressWarnings("deprecation")
                double g = perlinNoiseSampler.noise(
                        PerlinNoise.wrap(x * e), PerlinNoise.wrap(y * e), PerlinNoise.wrap(z * e), 0.0, 0.0
                );
                d += amplitudesArray[i] * g * f;
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
        return value - Math.floor(value * WRAP_RECRIPROCAL + 0.5) * WRAP_DOMAIN;
    }

    @Override
    public void fillColumn(
            double[] values, int x, int z, int yStart, int yCount,
            double scaleX, double scaleY, double scaleZ, double additionalScale) {

        Arrays.fill(values, 0.0);

        double inputFactor = this.lowestFreqInputFactor;
        double valueFactor = this.lowestFreqValueFactor;

        final double[] amplitudesArray = this.canvas$amplitudesArray;

        for (int i = 0; i < this.canvas$octaveSamplersCount; ++i) {
            ImprovedNoise octave = this.noiseLevels[i];
            if (octave != null) {
                double currentAmp = amplitudesArray[i] * valueFactor;

                double freqX = scaleX * inputFactor;
                double freqY = scaleY * inputFactor;
                double freqZ = scaleZ * inputFactor;

                ((ColumnNoiseFiller)(Object)octave).fillNoiseColumn(
                        values, x, z, yStart, yCount,
                        freqX, freqY, freqZ,
                        currentAmp
                );
            }
            inputFactor *= 2.0;
            valueFactor /= 2.0;
        }
    }

    @Override
    public void fillColumnWithFactor(double[] values, int x, int z, int yStart, int yCount, double scaleX, double scaleY, double scaleZ, double valueFactor_main) {
        Arrays.fill(values, 0.0);

        double inputFactor = this.lowestFreqInputFactor;
        double valueFactor = this.lowestFreqValueFactor;
        final double[] amplitudesArray = this.canvas$amplitudesArray;

        for (int i = 0; i < this.canvas$octaveSamplersCount; ++i) {
            ImprovedNoise octave = this.noiseLevels[i];
            if (octave != null) {
                double currentAmp = amplitudesArray[i] * valueFactor;

                double freqX = scaleX * inputFactor;
                double freqY = scaleY * inputFactor;
                double freqZ = scaleZ * inputFactor;

                ((ColumnNoiseFiller)(Object)octave).fillNoiseColumnWithFactor(
                        values, x, z, yStart, yCount,
                        freqX, freqY, freqZ,
                        currentAmp, valueFactor_main
                );
            }
            inputFactor *= 2.0;
            valueFactor /= 2.0;
        }
    }
}
