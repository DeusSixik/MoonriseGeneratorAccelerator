package dev.sixik.generator_accelerator.common.noise.mixin.noises;

import dev.sixik.generator_accelerator.common.noise.ColumnNoiseFiller;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkSliceProvider;
import dev.sixik.generator_accelerator.common.noise.region.GARegionalNoiseBrickCache;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(DensityFunctions.Noise.class)
public class MixinDensityFunctionsNoise {

    @Shadow
    @Final
    private DensityFunction.NoiseHolder noise;

    @Shadow
    @Final
    private double yScale;

    @Shadow
    @Final
    private double xzScale;

    /**
     * @author Sixik
     * @reason Reuse exact regional 3D noise bricks when a bound regional packet is available.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext context) {
        NormalNoise normalNoise = this.noise.noise();
        if (normalNoise == null) {
            return 0.0D;
        }
        return GARegionalNoiseBrickCache.samplePlainNormalNoise(normalNoise, context, this.xzScale, this.yScale);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public void fillArray(double[] ds, DensityFunction.ContextProvider ctx) {
        if (ctx instanceof NoiseChunk noiseChunk) {
            int x = noiseChunk.blockX();
            int z = noiseChunk.blockZ();

            NormalNoise normalNoise = this.noise.noise();

            if (normalNoise != null) {
                int startY = noiseChunk.cellStartBlockY;

                ((ColumnNoiseFiller) normalNoise).fillColumn(
                        ds,
                        x, z, startY, ds.length,
                        xzScale, yScale, xzScale,
                        0.0
                );
                return;
            }
        }

        if (ctx instanceof NoiseChunkSliceProvider provider) {
            int x = provider.noiseChunk().blockX();
            int z = provider.noiseChunk().blockZ();

            NormalNoise normalNoise = this.noise.noise();



            if (normalNoise != null) {
                int startY = provider.noiseChunk().cellStartBlockY;

                ((ColumnNoiseFiller) normalNoise).fillColumn(
                        ds,
                        x, z, startY, ds.length,
                        xzScale, yScale, xzScale,
                        0.0
                );
                return;
            }
        }

        ctx.fillAllDirectly(ds, (DensityFunctions.Noise)(Object)this);
    }
}
