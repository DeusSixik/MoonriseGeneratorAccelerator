package dev.sixik.generator_accelerator.common.noise.mixin.noises;

import dev.sixik.generator_accelerator.common.noise.ColumnNoiseFiller;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NormalNoise.class)
public abstract class MixinNormalNoise implements ColumnNoiseFiller {

    @Shadow
    @Final
    private PerlinNoise first;

    @Shadow
    @Final
    private PerlinNoise second;

    @Shadow
    @Final
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
                x * INPUT_FACTOR,
                y * INPUT_FACTOR,
                z * INPUT_FACTOR
        );
        return (v1 + v2) * valueFactor;
    }

    @Override
    public void fillColumn(double[] values, int x, int z, int yStart, int yCount,
                           double scaleX, double scaleY, double scaleZ, double outputFactor) {
        double finalFactor = this.valueFactor * outputFactor;
        ((ColumnNoiseFiller) this.first).fillColumnWithFactor(values, x, z, yStart, yCount,
                scaleX, scaleY, scaleZ, finalFactor);
        ((ColumnNoiseFiller) this.second).addColumnWithFactor(values, x, z, yStart, yCount,
                scaleX * INPUT_FACTOR,
                scaleY * INPUT_FACTOR,
                scaleZ * INPUT_FACTOR,
                finalFactor);
    }
}
