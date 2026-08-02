package dev.sixik.generator_accelerator.common.noise.mixin.noises;

import dev.sixik.generator_accelerator.common.noise.ColumnNoiseFiller;
import dev.sixik.generator_accelerator.common.noise.FastVectorNoise;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

@Mixin(PerlinNoise.class)
public abstract class MixinPerlinNoise implements ColumnNoiseFiller {

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

        canvas$fastOctaves = new FastVectorNoise[canvas$octaveSamplersCount];
        for (int i = 0; i < canvas$octaveSamplersCount; i++) {
            ImprovedNoise vanillaOctave = noiseLevels[i];
            if (vanillaOctave != null) {
                canvas$fastOctaves[i] = new FastVectorNoise(vanillaOctave.p, vanillaOctave.xo, vanillaOctave.yo, vanillaOctave.zo);
            }
        }
    }

    /**
     * @author Sixik
     * @reason Allocation-free scalar octave loop preserving vanilla order and double accumulation.
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        double result = 0.0;
        double inputFactor = this.lowestFreqInputFactor;
        double valueFactor = this.lowestFreqValueFactor;
        final double[] amplitudesArray = this.canvas$amplitudesArray;
        final FastVectorNoise[] fastOctaves = this.canvas$fastOctaves;

        for (int i = 0; i < this.canvas$octaveSamplersCount; ++i) {
            FastVectorNoise fastOctave = fastOctaves[i];
            if (fastOctave != null) {
                double sample = fastOctave.computeSingle(
                        FastVectorNoise.wrap(x * inputFactor),
                        FastVectorNoise.wrap(y * inputFactor),
                        FastVectorNoise.wrap(z * inputFactor));
                result += amplitudesArray[i] * sample * valueFactor;
            }
            inputFactor *= 2.0;
            valueFactor *= 0.5;
        }
        return result;
    }

    /**
     * @author Sixik
     * @reason Mirror vanilla wrap with no Mth call in the hot path.
     */
    @Overwrite
    public static double wrap(double value) {
        return FastVectorNoise.wrap(value);
    }

    @Override
    public void fillColumn(double[] values, int x, int z, int yStart, int yCount,
                           double scaleX, double scaleY, double scaleZ, double outputFactor) {
        fillColumnWithFactor(values, x, z, yStart, yCount, scaleX, scaleY, scaleZ, outputFactor);
    }

    @Override
    public void fillColumnWithFactor(double[] values, int x, int z, int yStart, int yCount,
                                     double scaleX, double scaleY, double scaleZ, double outputFactor) {
        Arrays.fill(values, 0.0);
        addColumnWithFactor(values, x, z, yStart, yCount, scaleX, scaleY, scaleZ, outputFactor);
    }

    @Override
    public void addColumnWithFactor(double[] values, int x, int z, int yStart, int yCount,
                                    double scaleX, double scaleY, double scaleZ, double outputFactor) {
        double inputFactor = this.lowestFreqInputFactor;
        double valueFactor = this.lowestFreqValueFactor;
        final double[] amplitudesArray = this.canvas$amplitudesArray;
        final FastVectorNoise[] fastOctaves = this.canvas$fastOctaves;

        for (int i = 0; i < this.canvas$octaveSamplersCount; ++i) {
            FastVectorNoise fastOctave = fastOctaves[i];
            if (fastOctave != null) {
                double currentAmplitude = amplitudesArray[i] * valueFactor * outputFactor;
                fastOctave.fillWrappedColumn(
                        values, x, z, yStart, yCount,
                        scaleX * inputFactor,
                        scaleY * inputFactor,
                        scaleZ * inputFactor,
                        currentAmplitude
                );
            }
            inputFactor *= 2.0;
            valueFactor *= 0.5;
        }
    }
}
