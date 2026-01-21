package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseBasedChunkGeneratorOptimizeDensity;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.density.DensityOptimizer;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomDensity;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator$optimize_density_functions implements NoiseBasedChunkGeneratorOptimizeDensity {

    @Mutable
    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

//    @Inject(method = "<init>", at = @At("RETURN"))
//    private void bts$init(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> holder, CallbackInfo ci) {
//
////        if(holder.isBound()) {
////
////            DensityOptimizer optimizer = new DensityOptimizer();
////
////            final NoiseGeneratorSettings settings = holder.value();
////            final NoiseRouter route = settings.noiseRouter();
////
////            final NoiseRouterCustomDensity customDensity = (NoiseRouterCustomDensity)(Object)route;
////
////            final DensityFunction[] array = customDensity.bts$getDensity();
////            for (int i = 0; i < array.length; i++) {
////                final DensityFunction originalDensity = array[i];
////                array[i] = DensityOptimizer.optimizeByASM(null, optimizer.optimize(originalDensity));
////            }
////        }
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
                array[i] = DensityOptimizer.optimizeByASM(null, optimizer.optimize(originalDensity));
            }

            customDensity.bts$setDensity(array);

            System.out.println("---------------");
            System.out.println(this.settings.value().noiseRouter().initialDensityWithoutJaggedness().getClass());
            this.settings = Holder.direct(settings);
            System.out.println(this.settings.value().noiseRouter().initialDensityWithoutJaggedness().getClass());
            System.out.println("---------------");
        }
    }
}
