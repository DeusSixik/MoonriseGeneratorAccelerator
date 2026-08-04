package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Optional fast predicate for cell-fill implementations that are often zero for a whole cell.
 */
public interface DfcCellZeroCheck {
    default boolean dfc$isAlwaysCellZero() {
        return false;
    }

    boolean dfc$isCellZero(NoiseChunk chunk);
}
