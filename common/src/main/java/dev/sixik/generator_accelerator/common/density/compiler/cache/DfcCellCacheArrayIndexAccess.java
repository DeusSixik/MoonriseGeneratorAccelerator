package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * NoiseChunk cell-cache wrapper that can read directly by the current
 * {@code NoiseChunk#arrayIndex} layout.
 */
public interface DfcCellCacheArrayIndexAccess extends DfcCellCacheAccess {
    double dfc$tryDirectReadByArrayIndex(DensityFunction.FunctionContext context);
}
