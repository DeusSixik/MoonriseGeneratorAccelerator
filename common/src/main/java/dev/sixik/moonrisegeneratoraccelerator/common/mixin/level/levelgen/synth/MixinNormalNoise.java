package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.synth;

import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NormalNoise.class)
public class MixinNormalNoise {

    @Shadow
    @Final
    private PerlinNoise first;

    @Shadow
    @Final
    private PerlinNoise second;

    @Shadow
    @Final
    private double valueFactor;

    /**
     * @author Sixik
     * @reason Micro Optimization
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        final double v1 = first.getValue(x, y, z);
        final double k = 1.0181268882175227;
        final double v2 = second.getValue(x * k, y * k, z * k);
        return (v1 + v2) * valueFactor;
    }
}
