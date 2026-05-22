package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Optional fast entrypoint for filling one {@link NoiseChunk} cell buffer.
 */
public interface DfcCellFillAccess {

    void dfc$fillCell(double[] out, NoiseChunk chunk);

    /**
     * Optional terrain-density cache entrypoint. Implementations that can collect the
     * all-positive/all-non-positive summary while writing {@code out} return true;
     * callers keep the old post-fill scan when this returns false.
     */
    default boolean dfc$fillCellAndCollectTerrainSummary(double[] out, NoiseChunk chunk) {
        dfc$fillCell(out, chunk);
        return false;
    }

    default void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        int length = chunk.cellWidth * chunk.cellWidth * chunk.cellHeight;
        double[] tmp = new double[length];
        dfc$fillCell(tmp, chunk);
        for (int i = 0; i < length; i++) {
            out[i] += tmp[i];
        }
    }
}
