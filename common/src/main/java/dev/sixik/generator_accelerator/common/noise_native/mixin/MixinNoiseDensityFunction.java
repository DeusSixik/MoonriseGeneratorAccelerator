package dev.sixik.generator_accelerator.common.noise_native.mixin;

import dev.sixik.generator_accelerator.common.noise_native.NativePtrGetter;
import dev.sixik.generator_accelerator.math.c3.NativeNoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(DensityFunctions.Noise.class)
public class MixinNoiseDensityFunction {

    @Shadow
    @Final
    private DensityFunction.NoiseHolder noise;

    @Shadow
    @Final
    @Deprecated
    private double xzScale;

    @Shadow
    @Final
    private double yScale;

    /**
     * @author Sixik
     * @reason Redirect to batch native method
     */
    @Overwrite
    public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
        final NormalNoise normalNoise = noise.noise();

        if (normalNoise != null && contextProvider instanceof NoiseChunk chunk) {
            NativeNoiseChunk.fillNoiseArrayDirectly(
                    ((NativePtrGetter)normalNoise).bts$getPtr(),
                    ds,
                    chunk.cellStartBlockX, chunk.cellStartBlockY, chunk.cellStartBlockZ,
                    chunk.cellWidth, chunk.cellHeight,
                    this.xzScale, this.yScale
            );
            return;
        }

        contextProvider.fillAllDirectly(ds, (DensityFunctions.Noise)(Object)this);
    }

}
