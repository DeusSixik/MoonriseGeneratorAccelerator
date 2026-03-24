package dev.sixik.generator_accelerator.common.noise_native.mixin;

import dev.sixik.generator_accelerator.common.noise.ColumnNoiseFiller;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkSliceProvider;
import dev.sixik.generator_accelerator.common.noise_native.NativePtrGetter;
import dev.sixik.generator_accelerator.math.c3.NativeNoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(DensityFunctions.ShiftNoise.class)
public interface MixinShiftNoise {

    @Shadow
    DensityFunction.NoiseHolder offsetNoise();

    /**
     * @author Sixik
     * @reason Redirect to batch native method
     */
    @Overwrite
    default void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
        final NormalNoise normalNoise = offsetNoise().noise();

        if(normalNoise != null) {

            if(contextProvider instanceof NoiseChunkSliceProvider provider) {
                NoiseChunk chunk = provider.noiseChunk();

                NativeNoiseChunk.fillSliceArrayDirectly(
                        ((NativePtrGetter)normalNoise).bts$getPtr(), ds,
                        chunk.blockX(), chunk.blockZ(),
                        chunk.cellNoiseMinY,
                        chunk.cellHeight,
                        chunk.cellCountY,
                        0.25, 0.25
                );
                return;
            } else if(contextProvider instanceof NoiseChunk chunk) {
                NativeNoiseChunk.fillNoiseArrayDirectly(
                        ((NativePtrGetter)normalNoise).bts$getPtr(),
                        ds,
                        chunk.cellStartBlockX, chunk.cellStartBlockY, chunk.cellStartBlockZ,
                        chunk.cellWidth, chunk.cellHeight,
                        0.25, 0.25
                );
                return;
            }
        }

        contextProvider.fillAllDirectly(ds, (DensityFunctions.ShiftNoise)(Object)this);
    }
}
