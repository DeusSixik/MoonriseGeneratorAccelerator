package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Per-cell 3D buffer: mirrors {@code NoiseChunk.CacheAllInCell#compute} when
 * {@code context ==} the owning {@link NoiseChunk} and interpolation is active.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public class NoiseChunkCacheAllInCellMixin implements DfcCellCacheAccess {

    @Shadow
    @Final
    private NoiseChunk field_36602;

    @Shadow
    @Final
    private double[] values;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (context != this.field_36602) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        if (!this.field_36602.interpolating) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int inX = this.field_36602.inCellX;
        int inY = this.field_36602.inCellY;
        int inZ = this.field_36602.inCellZ;
        int cellW = this.field_36602.cellWidth;
        int cellH = this.field_36602.cellHeight;
        if (inX < 0
                || inY < 0
                || inZ < 0
                || inX >= cellW
                || inY >= cellH
                || inZ >= cellW) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int index = (cellH - 1 - inY) * cellW * cellW + inX * cellW + inZ;
        return this.values[index];
    }
}
