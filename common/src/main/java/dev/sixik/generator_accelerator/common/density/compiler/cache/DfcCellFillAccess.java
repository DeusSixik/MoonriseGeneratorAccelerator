package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Optional fast entrypoint for filling one {@link NoiseChunk} cell buffer.
 */
public interface DfcCellFillAccess {

    void dfc$fillCell(double[] out, NoiseChunk chunk);

    default void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        if (this instanceof DfcZeroCellFillAccess) {
            return;
        }
        if (this instanceof DensityFunction density) {
            int cellW = chunk.cellWidth;
            int cellH = chunk.cellHeight;
            int idx = 0;
            chunk.arrayIndex = 0;
            for (int inCellY = cellH - 1; inCellY >= 0; inCellY--) {
                chunk.inCellY = inCellY;
                for (int inCellX = 0; inCellX < cellW; inCellX++) {
                    chunk.inCellX = inCellX;
                    for (int inCellZ = 0; inCellZ < cellW; inCellZ++) {
                        chunk.inCellZ = inCellZ;
                        chunk.arrayIndex = idx;
                        out[idx] += density.compute(chunk);
                        idx++;
                    }
                }
            }
            chunk.arrayIndex = idx;
            return;
        }
        int length = chunk.cellWidth * chunk.cellWidth * chunk.cellHeight;
        double[] tmp = new double[length];
        dfc$fillCell(tmp, chunk);
        for (int i = 0; i < length; i++) {
            out[i] += tmp[i];
        }
    }
}
