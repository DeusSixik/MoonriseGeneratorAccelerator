package dev.sixik.generator_accelerator.mixins.common_mixin.noise.noises;

import dev.sixik.generator_accelerator.common.noise.ColumnNoiseFiller;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.*;

@Mixin(NormalNoise.class)
public abstract class MixinNormalNoise implements ColumnNoiseFiller {

    private static final ThreadLocal<double[]> SECOND_BUFFER = ThreadLocal.withInitial(() -> new double[128]);

    @Shadow
    @Final
    private PerlinNoise first;

    @Shadow
    @Final
    private PerlinNoise second;

    @Shadow
    @Final
    @Mutable
    private double valueFactor;

    private static final double INPUT_FACTOR = 1.0181268882175227;

    /**
     * @author Sixik
     * @reason Micro Optimization
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        final double v1 = first.getValue(x, y, z);
        final double v2 = second.getValue(
                x * 1.0181268882175227,
                y * 1.0181268882175227,
                z * 1.0181268882175227
        );
        return (v1 + v2) * valueFactor;
    }

    @Override
    public void fillColumn(double[] values, int x, int z, int yStart, int yCount, double scaleX, double scaleY, double scaleZ, double additionalScale) {
        ((ColumnNoiseFiller) this.first).fillColumn(values, x, z, yStart, yCount, scaleX, scaleY, scaleZ, 0.0);

        double[] secondValues = SECOND_BUFFER.get();
        if(secondValues.length != yCount) {
            secondValues = new double[yCount];
            SECOND_BUFFER.set(secondValues);
        }

        ((ColumnNoiseFiller) this.second).fillColumnWithFactor(secondValues, x, z, yStart, yCount,
                scaleX * INPUT_FACTOR,
                scaleY * INPUT_FACTOR,
                scaleZ * INPUT_FACTOR,
                this.valueFactor + additionalScale
        );
    }
}
