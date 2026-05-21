package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

public record DfcOpenClChunkRequest(
        int firstChunkX,
        int firstChunkZ,
        int chunkCountX,
        int chunkCountZ,
        int minBlockY,
        int height,
        int cellWidth,
        int cellHeight,
        int maxOutputBytes,
        boolean validationEnabled) {
    public static DfcOpenClChunkRequest singleChunk(
            int chunkX,
            int chunkZ,
            int minBlockY,
            int height,
            int cellWidth,
            int cellHeight,
            int maxOutputBytes,
            boolean validationEnabled) {
        return new DfcOpenClChunkRequest(chunkX, chunkZ, 1, 1, minBlockY, height,
                cellWidth, cellHeight, maxOutputBytes, validationEnabled);
    }

    public int chunkCount() {
        return Math.multiplyExact(chunkCountX, chunkCountZ);
    }

    public boolean validShape() {
        return chunkCountX > 0
                && chunkCountZ > 0
                && height > 0
                && height % 16 == 0
                && cellWidth > 0
                && cellHeight > 0
                && maxOutputBytes >= 0;
    }
}
