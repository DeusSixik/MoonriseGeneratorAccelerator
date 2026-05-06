package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import net.minecraft.core.QuartPos;
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
public class NoiseChunkFlatCacheMixin implements DfcCellCacheAccess {

    @Shadow
    @Final
    private NoiseChunk field_36611;

    @Shadow
    @Final
    private double[][] values;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        int qx = QuartPos.fromBlock(context.blockX());
        int qz = QuartPos.fromBlock(context.blockZ());
        int i = qx - this.field_36611.firstNoiseX;
        int j = qz - this.field_36611.firstNoiseZ;
        int w = this.values.length;
        if (i < 0 || j < 0 || i >= w || j >= w) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        return this.values[i][j];
    }
}
