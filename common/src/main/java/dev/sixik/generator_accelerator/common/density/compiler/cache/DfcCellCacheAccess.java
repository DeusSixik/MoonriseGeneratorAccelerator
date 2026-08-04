package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Implemented by {@link net.minecraft.world.level.levelgen.NoiseChunk} cache wrappers
 * (flat, 2D, all-in-cell, cache-once) for optional O(1) buffer reads (see
 * {@link DfcCacheFastPath}). Not wired into generated {@code compute} bytecode by
 * default — a universal wrapper would tax every extern call.
 */
public interface DfcCellCacheAccess {

    /**
     * If the current context is a cache hit, return the packed cell value; otherwise
     * returns {@code NaN} with the sentinel bit pattern {@link DfcCacheFastPath#MISS_BITS}.
     */
    double dfc$tryDirectRead(DensityFunction.FunctionContext context);
}
