package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomDensity;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseRouter.class)
public abstract class MixinNoiseRouter$add_custom_optimizer implements NoiseRouterCustomDensity {

    @Mutable
    @Shadow
    @Final
    private DensityFunction barrierNoise;

    @Mutable
    @Shadow
    @Final
    private DensityFunction fluidLevelFloodednessNoise;

    @Mutable
    @Shadow
    @Final
    private DensityFunction fluidLevelSpreadNoise;

    @Mutable
    @Shadow
    @Final
    private DensityFunction lavaNoise;

    @Mutable
    @Shadow
    @Final
    private DensityFunction temperature;

    @Mutable
    @Shadow
    @Final
    private DensityFunction vegetation;

    @Mutable
    @Shadow
    @Final
    private DensityFunction continents;

    @Mutable
    @Shadow
    @Final
    private DensityFunction erosion;

    @Mutable
    @Shadow
    @Final
    private DensityFunction depth;

    @Mutable
    @Shadow
    @Final
    private DensityFunction ridges;

    @Mutable
    @Shadow
    @Final
    private DensityFunction initialDensityWithoutJaggedness;

    @Mutable
    @Shadow
    @Final
    private DensityFunction finalDensity;

    @Mutable
    @Shadow
    @Final
    private DensityFunction veinToggle;

    @Mutable
    @Shadow
    @Final
    private DensityFunction veinRidged;

    @Mutable
    @Shadow
    @Final
    private DensityFunction veinGap;

    @Override
    public void bts$setDensity(DensityFunction[] array) {
        barrierNoise =                      array[0];
        fluidLevelFloodednessNoise =        array[1];
        fluidLevelSpreadNoise =             array[2];
        lavaNoise =                         array[3];
        temperature =                       array[4];
        vegetation =                        array[5];
        continents =                        array[6];
        erosion =                           array[7];
        depth =                             array[8];
        ridges =                            array[9];
        initialDensityWithoutJaggedness =   array[10];
        finalDensity =                      array[11];
        veinToggle =                        array[12];
        veinRidged =                        array[13];
        veinGap =                           array[14];
    }

    @Override
    public DensityFunction[] bts$getDensity() {
        return new DensityFunction[] {
                barrierNoise, fluidLevelFloodednessNoise, fluidLevelSpreadNoise, lavaNoise, temperature, vegetation,
                continents, erosion, depth, ridges, initialDensityWithoutJaggedness, finalDensity, veinToggle,
                veinRidged, veinGap
        };
    }
}
