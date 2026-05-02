package dev.sixik.generator_accelerator.common.noise.mixin.noises;

import dev.sixik.generator_accelerator.common.noise.ColumnNoiseFiller;
import dev.sixik.generator_accelerator.common.noise.FastVectorNoise;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.Mth;
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

    @Unique
    private FastVectorNoise[] canvas$fastOctaves;

    @Inject(method = "<init>", at = @At("TAIL"))
    protected void bts$createLegacyForBlendedNoise(CallbackInfo ci) {
        canvas$octaveSamplersCount = noiseLevels.length;
        canvas$amplitudesArray = amplitudes.toDoubleArray();

        // Создаем массив наших быстрых векторизованных сэмплеров
        canvas$fastOctaves = new FastVectorNoise[canvas$octaveSamplersCount];
        for (int i = 0; i < canvas$octaveSamplersCount; i++) {
            ImprovedNoise vanillaOctave = noiseLevels[i];
            if (vanillaOctave != null) {
                byte[] p = vanillaOctave.p;
                double xo = vanillaOctave.xo;
                double yo = vanillaOctave.yo;
                double zo = vanillaOctave.zo;
                canvas$fastOctaves[i] = new FastVectorNoise(p, xo, yo, zo);
            }
        }
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

        final double[] amplitudesArray = this.canvas$amplitudesArray;

        for (int i = 0; i < this.canvas$octaveSamplersCount; ++i) {
            FastVectorNoise fastOctave = this.canvas$fastOctaves[i];

            if (fastOctave != null) {
                // wrap() остается нашим, оптимизированным из MixinPerlinNoise
                double g = fastOctave.computeSingle(wrap(x * e), wrap(y * e), wrap(z * e));
                d += amplitudesArray[i] * g * f;
            }
            e *= 2.0;
            f *= 0.5;
        }
        return d;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public static double wrap(double value) {
        return value - Mth.floor(value * WRAP_RECRIPROCAL + 0.5) * WRAP_DOMAIN;
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
            FastVectorNoise fastOctave = this.canvas$fastOctaves[i];

            if (fastOctave != null) {
                // Домножаем на additionalScale, если ванилла его передает
                double currentAmp = amplitudesArray[i] * valueFactor * additionalScale;

                double freqX = scaleX * inputFactor;
                double freqY = scaleY * inputFactor;
                double freqZ = scaleZ * inputFactor;

                // Используем наш векторизованный метод. valueFactor_main = 1.0
                fastOctave.fillColumnVectorized(
                        values, x, z, yStart, yCount,
                        freqX, freqY, freqZ,
                        currentAmp, 1.0
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
            // ИСПОЛЬЗУЕМ НАШ ВЕКТОРИЗОВАННЫЙ СЭМПЛЕР
            FastVectorNoise fastOctave = this.canvas$fastOctaves[i];
            if (fastOctave != null) {
                double currentAmp = amplitudesArray[i] * valueFactor;

                double freqX = scaleX * inputFactor;
                double freqY = scaleY * inputFactor;
                double freqZ = scaleZ * inputFactor;

                // ВЫЗОВ СВЕРХБЫСТРОГО МЕТОДА
                fastOctave.fillColumnVectorized(
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
