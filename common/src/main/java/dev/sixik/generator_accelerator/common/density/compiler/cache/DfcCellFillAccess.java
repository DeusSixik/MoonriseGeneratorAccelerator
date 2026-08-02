package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.NoiseChunk;

import java.util.Arrays;

/**
 * Optional fast entrypoint for filling one {@link NoiseChunk} cell buffer.
 */
public interface DfcCellFillAccess {

    ThreadLocal<double[]> DFC_ACCUMULATE_SCRATCH = ThreadLocal.withInitial(() -> new double[0]);

    void dfc$fillCell(double[] out, NoiseChunk chunk);

    default void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        int length = chunk.cellWidth * chunk.cellWidth * chunk.cellHeight;
        double[] tmp = DFC_ACCUMULATE_SCRATCH.get();
        if (tmp.length < length) {
            tmp = new double[length];
            DFC_ACCUMULATE_SCRATCH.set(tmp);
        } else {
            Arrays.fill(tmp, 0, length, 0.0D);
        }
        dfc$fillCell(tmp, chunk);
        for (int i = 0; i < length; i++) {
            out[i] += tmp[i];
        }
    }
}
