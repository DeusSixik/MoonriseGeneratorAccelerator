package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import dev.sixik.generator_accelerator.common.noise.NoiseChunk$FlatCache$FlatArray;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mirrors {@code NoiseChunk.FlatCache#compute} in-buffer path
 * (2D layout over quart indices).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$FlatCache")
public abstract class NoiseChunkFlatCacheMixin
        implements DfcCellCacheAccess, NoiseChunk$FlatCache$FlatArray {

    @Shadow
    @Final
    private NoiseChunk field_36611;

    @Shadow
    @Final
    private DensityFunction noiseFiller;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        final int side = field_36611.noiseSizeXZ + 1;

        final int k = (context.blockX() >> 2) - field_36611.firstNoiseX;
        final int l = (context.blockZ() >> 2) - field_36611.firstNoiseZ;

        // Flat cache
        if (k >= 0 && l >= 0 && k < side && l < side) {
            return bts$getArray()[k * side + l];
        }

        return this.noiseFiller.compute(context);
    }
}
