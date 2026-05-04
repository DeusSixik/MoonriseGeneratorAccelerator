package dev.sixik.generator_accelerator.mixins.common_mixin.density;

import dev.sixik.generator_accelerator.common.density.utils.DensityOptimizer;
import dev.sixik.generator_accelerator.common.density.utils.NoiseBasedChunkGeneratorOptimizeDensity;
import dev.sixik.generator_accelerator.common.density.utils.NoiseRouterCustomDensity;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator$optimize_density_functions implements NoiseBasedChunkGeneratorOptimizeDensity {

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

//    @Inject(method = "<init>", at = @At("RETURN"))
//    private void bts$init(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> holder, CallbackInfo ci) {
//        if(holder.isBound()) {
//
//            DensityOptimizer optimizer = new DensityOptimizer();
//
//            final NoiseGeneratorSettings settings = holder.value();
//            final NoiseRouter route = settings.noiseRouter();
//
//            final NoiseRouterCustomDensity customDensity = (NoiseRouterCustomDensity)(Object)route;
//
//            final DensityFunction[] array = customDensity.bts$getDensity();
//            for (int i = 0; i < array.length; i++) {
//                final DensityFunction originalDensity = array[i];
//                array[i] = optimizer.optimize(originalDensity);
//            }
//            customDensity.bts$setDensity(array);
//        }
//    }

    @Override
    public void bts$applyDensityOptimize() {
        if(settings.isBound()) {
            final Holder<NoiseGeneratorSettings> s = settings;
            DensityOptimizer optimizer = new DensityOptimizer();
            final NoiseGeneratorSettings settings = s.value();
            final NoiseRouter route = settings.noiseRouter();
            final NoiseRouterCustomDensity customDensity = (NoiseRouterCustomDensity)(Object)route;
            final DensityFunction[] array = customDensity.bts$getDensity();
            for (int i = 0; i < array.length; i++) {
                final DensityFunction originalDensity = array[i];
                array[i] = optimizer.optimize(originalDensity);
            }

            customDensity.bts$setDensity(array);
        }
    }
}
