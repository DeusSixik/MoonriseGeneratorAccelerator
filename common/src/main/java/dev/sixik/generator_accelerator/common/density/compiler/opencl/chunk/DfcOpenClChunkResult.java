package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import java.util.Objects;

public final class DfcOpenClChunkResult {
    public static final int POST_PROCESS_FLAG = 1 << 31;
    private static final int BLOCK_STATE_MASK = ~POST_PROCESS_FLAG;

    private final double[] densities;
    private final int[] packedBlocks;

    private DfcOpenClChunkResult(double[] densities, int[] packedBlocks) {
        this.densities = densities;
        this.packedBlocks = packedBlocks;
    }

    public static DfcOpenClChunkResult densities(double[] densities) {
        return new DfcOpenClChunkResult(Objects.requireNonNull(densities, "densities"), null);
    }

    public static DfcOpenClChunkResult packedBlocks(int[] packedBlocks) {
        return new DfcOpenClChunkResult(null, Objects.requireNonNull(packedBlocks, "packedBlocks"));
    }

    public boolean hasDensities() {
        return densities != null;
    }

    public boolean hasPackedBlocks() {
        return packedBlocks != null;
    }

    public int densityLength() {
        return densities == null ? 0 : densities.length;
    }

    public int packedLength() {
        return packedBlocks == null ? 0 : packedBlocks.length;
    }

    public double density(int index) {
        return densities[index];
    }

    public int packedBlock(int index) {
        return packedBlocks[index];
    }

    public int blockStateId(int index) {
        return packedBlocks[index] & BLOCK_STATE_MASK;
    }

    public boolean requiresPostProcessing(int index) {
        return (packedBlocks[index] & POST_PROCESS_FLAG) != 0;
    }
}
