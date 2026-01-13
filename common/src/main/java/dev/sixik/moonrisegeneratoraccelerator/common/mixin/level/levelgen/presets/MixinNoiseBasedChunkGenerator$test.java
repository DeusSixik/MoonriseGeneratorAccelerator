package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.presets;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensityOptimizer;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseRouterCustomDensity;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator$test {

    private static final DensityOptimizer OPTIMIZER = new DensityOptimizer();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$init(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> holder, CallbackInfo ci) {
        if(holder.isBound()) {
            final NoiseGeneratorSettings settings = holder.value();
            final NoiseRouter route = settings.noiseRouter();

            final NoiseRouterCustomDensity customDensity = (NoiseRouterCustomDensity)(Object)route;

            final DensityFunction[] array = customDensity.bts$getDensity();
            for (int i = 0; i < array.length; i++) {
                final DensityFunction originalDensity = array[i];
                array[i] = OPTIMIZER.optimizeByASM(originalDensity, originalDensity);
            }
            customDensity.bts$setDensity(array);
        }
    }
}
