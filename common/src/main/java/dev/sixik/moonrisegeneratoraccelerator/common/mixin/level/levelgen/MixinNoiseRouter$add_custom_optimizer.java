package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomDensity;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomOptimizer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseRouter.class)
public abstract class MixinNoiseRouter$add_custom_optimizer implements NoiseRouterCustomOptimizer, NoiseRouterCustomDensity {

    @Shadow
    public abstract NoiseRouter mapAll(DensityFunction.Visitor arg);

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
    public NoiseRouter bts$routeOptimize() {
        return null;
    }

    @Override
    public void bts$setDensity(DensityFunction[] array) {
        final DensityFunction[] nArray = array;

//        barrierNoise =                      nArray[0];
//        fluidLevelFloodednessNoise =        nArray[1];
//        fluidLevelSpreadNoise =             nArray[2];
//        lavaNoise =                         nArray[3];
//        temperature =                       nArray[4];
//        vegetation =                        nArray[5];
//        continents =                        nArray[6];
//        erosion =                           nArray[7];
//        depth =                             nArray[8];
//        ridges =                            nArray[9];
        initialDensityWithoutJaggedness =   nArray[10];
//        finalDensity =                      nArray[11];
//        veinToggle =                        nArray[12];
//        veinRidged =                        nArray[13];
//        veinGap =                           nArray[14];
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
