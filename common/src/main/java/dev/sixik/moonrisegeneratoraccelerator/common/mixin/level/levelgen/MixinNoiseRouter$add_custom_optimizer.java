package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensityOptimizer;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomOptimizer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseRouter.class)
public abstract class MixinNoiseRouter$add_custom_optimizer implements NoiseRouterCustomOptimizer {

    @Shadow
    public abstract NoiseRouter mapAll(DensityFunction.Visitor visitor);

    @Override
    public NoiseRouter bts$routeOptimize() {
        final DensityOptimizer optimizer = new DensityOptimizer();

        return mapAll(new DensityFunction.Visitor() {
            @Override
            public @NotNull DensityFunction apply(DensityFunction function) {
                return optimizer.optimize(function);
            }

            @Override
            public DensityFunction.@NotNull NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                return noise;
            }
        });
    }
}
