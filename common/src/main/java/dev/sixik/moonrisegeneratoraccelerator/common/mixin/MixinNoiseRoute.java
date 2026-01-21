package dev.sixik.moonrisegeneratoraccelerator.common.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sixik.density_compiler.utils.wrappers.DensityFunctionSerializerWrapper;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.*;

import java.util.function.Function;

@Mixin(NoiseRouter.class)
public abstract class MixinNoiseRoute {

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
