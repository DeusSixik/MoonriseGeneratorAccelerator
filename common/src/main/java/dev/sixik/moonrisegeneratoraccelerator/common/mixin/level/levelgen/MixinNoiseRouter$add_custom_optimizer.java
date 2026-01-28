package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomDensity;
import dev.sixik.moonrisegeneratoraccelerator.common.utils.wrappers.DensityFunctionSerializerWrapper;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.*;

import java.util.function.Function;

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

    @Shadow
    private static RecordCodecBuilder<NoiseRouter, DensityFunction> field(String string, Function<NoiseRouter, DensityFunction> function) {
        throw new RuntimeException();
    }

    @Shadow
    @Final
    @Mutable
    public static Codec<NoiseRouter> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    field("barrier", s -> bts$getOrig(s.barrierNoise())),
                    field("fluid_level_floodedness", s -> bts$getOrig(s.fluidLevelFloodednessNoise())),
                    field("fluid_level_spread", s -> bts$getOrig(s.fluidLevelSpreadNoise())),
                    field("lava", s -> bts$getOrig(s.lavaNoise())),
                    field("temperature", s -> bts$getOrig(s.temperature())),
                    field("vegetation", s -> bts$getOrig(s.vegetation())),
                    field("continents", s -> bts$getOrig(s.continents())),
                    field("erosion", s -> bts$getOrig(s.erosion())),
                    field("depth", s -> bts$getOrig(s.depth())),
                    field("ridges", s -> bts$getOrig(s.ridges())),
                    field("initial_density_without_jaggedness", s -> bts$getOrig(s.initialDensityWithoutJaggedness())),
                    field("final_density", s -> bts$getOrig(s.finalDensity())),
                    field("vein_toggle", s -> bts$getOrig(s.veinToggle())),
                    field("vein_ridged", s -> bts$getOrig(s.veinRidged())),
                    field("vein_gap", s -> bts$getOrig(s.veinGap()))
            ).apply(instance, NoiseRouter::new));

    @Unique
    private static DensityFunction bts$getOrig(DensityFunction fn) {
        return DensityFunctionSerializerWrapper.getOriginal(fn);
    }
}
