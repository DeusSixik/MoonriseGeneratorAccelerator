package dev.sixik.generator_accelerator.common.density.mixin;

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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-cell 3D buffer: mirrors {@code NoiseChunk.CacheAllInCell#compute} when
 * {@code context ==} the owning {@link NoiseChunk} and interpolation is active.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public class NoiseChunkCacheAllInCellMixin implements DfcCellCacheAccess, DfcCellCacheCompiledFillerAccess {

    @Unique
    private static final String DFC_LAZY_CELL_CACHE_COMPILE_PROPERTY = "ga.dfc.lazyCellCacheCompile";

    @Unique
    private static final String DFC_LAZY_CELL_CACHE_COMPILE_MAX_PROPERTY = "ga.dfc.lazyCellCacheCompile.max";

    @Unique
    private static final AtomicInteger DFC_LAZY_CELL_CACHE_COMPILES = new AtomicInteger();

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
        if (index < 0 || index >= this.values.length) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        return this.values[index];
    }

    @Override
    public DfcCellFillAccess dfc$getOrCompileCellFiller() {
        if (!this.dfc$compileAttempted) {
            this.dfc$compileAttempted = true;
            if (!dfc$shouldAttemptLazyCellCacheCompile()) {
                return null;
            }
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

    @Unique
    private static boolean dfc$shouldAttemptLazyCellCacheCompile() {
        if (!Boolean.getBoolean(DFC_LAZY_CELL_CACHE_COMPILE_PROPERTY)) {
            return false;
        }
        int maxCompiles = Math.max(0, Integer.getInteger(DFC_LAZY_CELL_CACHE_COMPILE_MAX_PROPERTY, 128));
        while (true) {
            int current = DFC_LAZY_CELL_CACHE_COMPILES.get();
            if (current >= maxCompiles) {
                return false;
            }
            if (DFC_LAZY_CELL_CACHE_COMPILES.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }
}
