package dev.sixik.generator_accelerator.mixins.common_mixin.noise.noises;

import dev.sixik.generator_accelerator.common.noise.ColumnNoiseFiller;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkSliceProvider;
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
    default void fillArray(double[] ds, DensityFunction.ContextProvider ctx) {
        final NormalNoise normalNoise = offsetNoise().noise();

        if(normalNoise != null) {

            if (ctx instanceof NoiseChunk noiseChunk) {
                int x = noiseChunk.blockX();
                int z = noiseChunk.blockZ();

                int startY = noiseChunk.cellStartBlockY;

                ((ColumnNoiseFiller) normalNoise).fillColumn(
                        ds,
                        x, z, startY, ds.length,
                        0.25, 0.25, 0.25,
                        4.0
                );
                return;
            }

            if(ctx instanceof NoiseChunkSliceProvider provider) {
                int x = provider.noiseChunk().blockX();
                int z = provider.noiseChunk().blockZ();

                int startY = provider.noiseChunk().cellStartBlockY;
                ((ColumnNoiseFiller) normalNoise).fillColumn(
                        ds,
                        x, z, startY, ds.length,
                        0.25, 0.25, 0.25,
                        4.0
                );
                return;
            }
        }

        ctx.fillAllDirectly(ds, (DensityFunctions.ShiftNoise)(Object)this);
    }
}
