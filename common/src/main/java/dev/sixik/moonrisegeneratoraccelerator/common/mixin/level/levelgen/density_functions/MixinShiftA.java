package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.density_functions;

import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DensityFunctions.ShiftA.class)
public abstract class MixinShiftA implements DensityFunctions.ShiftNoise {

    @Nullable
    @Unique
    private NormalNoise bts$noise;

    @Unique
    private double bts$maxValue = 0.0;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(NoiseHolder noiseHolder, CallbackInfo ci) {
        bts$noise = noiseHolder.noise();
        bts$maxValue = noiseHolder.maxValue() * 4.0;
    }

    @Override
    public double maxValue() {
        return bts$maxValue;
    }

    @Override
    public double compute(double d, double e, double f) {
        return bts$noise.getValue(d * 0.25, e * 0.25, f * 0.25) * 4.0;
    }
}
