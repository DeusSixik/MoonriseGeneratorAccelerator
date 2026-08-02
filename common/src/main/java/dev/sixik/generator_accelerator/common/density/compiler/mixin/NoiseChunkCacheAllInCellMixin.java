package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheCompiledFillerAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Per-cell 3D buffer: mirrors {@code NoiseChunk.CacheAllInCell#compute} when
 * {@code context ==} the owning {@link NoiseChunk} and interpolation is active.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public class NoiseChunkCacheAllInCellMixin implements DfcCellCacheAccess, DfcCellCacheCompiledFillerAccess {

    @Shadow
    @Final
    private NoiseChunk field_36602;

    @Shadow
    @Final
    private double[] values;

    @Shadow
    @Final
    private DensityFunction noiseFiller;

    @Unique
    private boolean dfc$compileAttempted;

    @Unique
    private DfcCellFillAccess dfc$compiledCellFiller;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (context != this.field_36602) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        return dfc$tryDirectRead(this.field_36602);
    }

    @Override
    public double dfc$tryDirectRead(NoiseChunk chunk) {
        if (chunk != this.field_36602) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        if (!chunk.interpolating) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int inX = chunk.inCellX;
        int inY = chunk.inCellY;
        int inZ = chunk.inCellZ;
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        if (inX < 0
                || inY < 0
                || inZ < 0
                || inX >= cellW
                || inY >= cellH
                || inZ >= cellW) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int index = (cellH - 1 - inY) * cellW * cellW + inX * cellW + inZ;
        if (index < 0 || index >= this.values.length) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        return this.values[index];
    }

    @Override
    public DfcCellFillAccess dfc$getOrCompileCellFiller() {
        if (!this.dfc$compileAttempted) {
            this.dfc$compileAttempted = true;
            try {
                DensityFunction compiled = Compiler.compile(this.noiseFiller);
                if (compiled instanceof DfcCellFillAccess access) {
                    this.dfc$compiledCellFiller = access;
                }
            } catch (Throwable ignored) {
                // Compiler is fail-soft; keep vanilla fillArray as the fallback for this cache.
                this.dfc$compiledCellFiller = null;
            }
        }
        return this.dfc$compiledCellFiller;
    }
}
