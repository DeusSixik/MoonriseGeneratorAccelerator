package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.noise.ColumnNoiseFiller;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.noise.NoiseChunkSliceProvider;
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
     * @reason Перехват fillArray для использования колоночной оптимизации
     */
    @Overwrite
    default void fillArray(double[] ds, DensityFunction.ContextProvider ctx) {
        if (ctx instanceof NoiseChunk noiseChunk) {
            int x = noiseChunk.blockX();
            int z = noiseChunk.blockZ();

            NormalNoise normalNoise = this.offsetNoise().noise();
            if (normalNoise != null) {
                int startY = noiseChunk.cellStartBlockY;

                ((ColumnNoiseFiller) normalNoise).fillColumn(
                        ds,
                        x, z, startY, ds.length,
                        0.25, 0.25, 0.25,
                        4.0
                );
                return;
            }
        }

        if(ctx instanceof NoiseChunkSliceProvider provider) {
            int x = provider.noiseChunk().blockX();
            int z = provider.noiseChunk().blockZ();

            NormalNoise normalNoise = this.offsetNoise().noise();

            if (normalNoise != null) {
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

        ctx.fillAllDirectly(ds, (DensityFunctions.ShiftNoise) this);
    }
}
