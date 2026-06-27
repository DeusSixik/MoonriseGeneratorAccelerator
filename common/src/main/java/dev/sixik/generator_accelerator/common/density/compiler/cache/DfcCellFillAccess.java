package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Optional fast entrypoint for filling one {@link NoiseChunk} cell buffer.
 */
public interface DfcCellFillAccess {

    void dfc$fillCell(double[] out, NoiseChunk chunk);

    default void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        int length = chunk.cellWidth * chunk.cellWidth * chunk.cellHeight;
        double[] tmp = new double[length];
        dfc$fillCell(tmp, chunk);
        for (int i = 0; i < length; i++) {
            out[i] += tmp[i];
        }
    }
}
