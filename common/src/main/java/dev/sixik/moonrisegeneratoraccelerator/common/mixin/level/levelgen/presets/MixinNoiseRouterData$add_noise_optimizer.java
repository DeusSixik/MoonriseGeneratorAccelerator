package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.presets;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensityOptimizer;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NoiseRouterData.class)
public class MixinNoiseRouterData$add_noise_optimizer {

    @Unique
    private static final DensityOptimizer DENSITY_OPTIMIZER = new DensityOptimizer();

//    @WrapOperation(method = "overworld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions;rangeChoice(Lnet/minecraft/world/level/levelgen/DensityFunction;DDLnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
//    private static DensityFunction bts$overworld$use_density_optimizer(DensityFunction arg, double d, double e, DensityFunction arg2, DensityFunction arg3, Operation<DensityFunction> original) {
//        final DensityFunction originalFunction = original.call(arg, d, e, arg2, arg3);
//        return DENSITY_OPTIMIZER.optimizeByASM(originalFunction, DENSITY_OPTIMIZER.optimize(originalFunction));
//    }

//    @Inject(method = {"noodle", "registerAndWrap", "getFunction", "peaksAndValleys(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;",
//     ""}, at = @At("RETURN"), cancellable = true)
//    private static void bts$density_methods$redirect(CallbackInfoReturnable<DensityFunction> cir) {
//        final DensityFunction density = cir.getReturnValue();
//        if(density != null) {
//            cir.setReturnValue(DENSITY_OPTIMIZER.optimizeByASM(density, DENSITY_OPTIMIZER.optimize(density)););
//
//        }
//    }
}
