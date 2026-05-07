package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Optional fast entrypoint for filling one {@link NoiseChunk} cell buffer.
 */
public interface DfcCellFillAccess {

    void dfc$fillCell(double[] out, NoiseChunk chunk);
}
