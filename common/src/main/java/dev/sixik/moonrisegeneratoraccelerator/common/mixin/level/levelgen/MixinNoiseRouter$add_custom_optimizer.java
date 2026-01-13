package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensityOptimizer;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomOptimizer;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.OptimizationVisitor;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseRouter.class)
public abstract class MixinNoiseRouter$add_custom_optimizer implements NoiseRouterCustomOptimizer {

    @Shadow
    public abstract NoiseRouter mapAll(DensityFunction.Visitor arg);

    @Unique
    private final DensityOptimizer bts$optimizer = new DensityOptimizer();

    @Override
    public NoiseRouter bts$routeOptimize() {
        return mapAll(new OptimizationVisitor());
    }

    @Redirect(method = "mapAll", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;", ordinal = 10))
    public DensityFunction bts$mapAll$redirect(DensityFunction instance, DensityFunction.Visitor visitor) {
//        if (visitor instanceof OptimizationVisitor) {
//            return instance.mapAll(visitor);
//        }

//        DensityFunction mapped = instance.mapAll(visitor);
        return bts$optimizer.optimizeByASM(instance, instance);
    }

}
