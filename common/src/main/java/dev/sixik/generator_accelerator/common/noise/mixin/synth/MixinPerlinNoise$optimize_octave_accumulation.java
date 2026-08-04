package dev.sixik.generator_accelerator.common.noise.mixin.synth;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PerlinNoise.class)
public abstract class MixinPerlinNoise$optimize_octave_accumulation {

    @Shadow
    @Final
    private ImprovedNoise[] noiseLevels;

    @Shadow
    @Final
    private DoubleList amplitudes;

    @Shadow
    @Final
    private double lowestFreqValueFactor;

    @Shadow
    @Final
    private double lowestFreqInputFactor;

    @Shadow
    public static double wrap(double value) {
        throw new AssertionError();
    }

    /**
     * @author Sixik
     * @reason Reduce repeated field and list lookups in the octave accumulation loop.
     */
    @Overwrite
    @Deprecated
    public double getValue(final double x, final double y, final double z,
                           final double yScale, final double yMax, final boolean useFixedY) {
        final ImprovedNoise[] levels = this.noiseLevels;
        final DoubleList amplitudes = this.amplitudes;
        final int levelCount = levels.length;

        double result = 0.0;
        double inputFactor = this.lowestFreqInputFactor;
        double valueFactor = this.lowestFreqValueFactor;

        for (int i = 0; i < levelCount; i++) {
            final ImprovedNoise noise = levels[i];
            if (noise != null) {
                final double wrappedX = wrap(x * inputFactor);
                final double wrappedY = useFixedY ? -noise.yo : wrap(y * inputFactor);
                final double wrappedZ = wrap(z * inputFactor);
                result += amplitudes.getDouble(i) * noise.noise(wrappedX, wrappedY, wrappedZ, yScale * inputFactor, yMax * inputFactor) * valueFactor;
            }

            inputFactor *= 2.0;
            valueFactor *= 0.5;
        }

        return result;
    }
}
