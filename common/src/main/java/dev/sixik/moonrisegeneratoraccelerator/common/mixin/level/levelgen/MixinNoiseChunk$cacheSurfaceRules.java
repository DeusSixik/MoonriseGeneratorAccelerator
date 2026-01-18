package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NoiseChunk.class)
public class MixinNoiseChunk$cacheSurfaceRules {

//    @Unique
//    private static final Map<RandomState, NoiseChunk.BlockStateFiller> ORE_FILLER_CACHE = new ConcurrentHashMap<>();
//
//    @Unique
//    private RandomState moonrise_generator_accelerator$randomState;

//    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"))
//    private void bts$init(int i, RandomState randomState, int j, int k, NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifierOrMarker, NoiseGeneratorSettings noiseGeneratorSettings, Aquifer.FluidPicker fluidPicker, Blender blender, CallbackInfo ci) {
//        this.moonrise_generator_accelerator$randomState = randomState;
//    }
//
//    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"))
//    private NoiseChunk.BlockStateFiller bts$init$redirect_create_ore_context(
//            DensityFunction densityFunction,
//            DensityFunction densityFunction2,
//            DensityFunction densityFunction3,
//            PositionalRandomFactory positionalRandomFactory,
//            Operation<NoiseChunk.BlockStateFiller> original
//    ) {
//        return ORE_FILLER_CACHE.computeIfAbsent(moonrise_generator_accelerator$randomState, rs ->
//                original.call(densityFunction, densityFunction2, densityFunction3, positionalRandomFactory)
//        );
//    }
}
