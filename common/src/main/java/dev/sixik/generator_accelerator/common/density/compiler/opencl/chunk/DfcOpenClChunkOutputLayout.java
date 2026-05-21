package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

public final class DfcOpenClChunkOutputLayout {
    private static final int CHUNK_WIDTH = 16;
    private static final int BLOCKS_PER_LAYER = CHUNK_WIDTH * CHUNK_WIDTH;

    private final DfcOpenClChunkRequest request;
    private final int valuesPerChunk;
    private final int totalValues;

    private DfcOpenClChunkOutputLayout(DfcOpenClChunkRequest request) {
        this.request = request;
        this.valuesPerChunk = Math.multiplyExact(BLOCKS_PER_LAYER, request.height());
        this.totalValues = Math.multiplyExact(valuesPerChunk, request.chunkCount());
    }

    public static DfcOpenClChunkOutputLayout forRequest(DfcOpenClChunkRequest request) {
        if (request == null || !request.validShape()) {
            throw new IllegalArgumentException("invalid chunk request");
        }
        return new DfcOpenClChunkOutputLayout(request);
    }

    public int valuesPerChunk() {
        return valuesPerChunk;
    }

    public int totalValues() {
        return totalValues;
    }

    public int densityOutputBytes() {
        return Math.multiplyExact(totalValues, Double.BYTES);
    }

    public int packedBlockOutputBytes() {
        return Math.multiplyExact(totalValues, Integer.BYTES);
    }

    public int index(int chunkIndex, int localX, int localY, int localZ) {
        if (chunkIndex < 0 || chunkIndex >= request.chunkCount()
                || localX < 0 || localX >= CHUNK_WIDTH
                || localY < 0 || localY >= request.height()
                || localZ < 0 || localZ >= CHUNK_WIDTH) {
            throw new IndexOutOfBoundsException("chunk output index outside layout");
        }
        return chunkIndex * valuesPerChunk + localY * BLOCKS_PER_LAYER + localZ * CHUNK_WIDTH + localX;
    }

    public int chunkIndex(int index) {
        checkIndex(index);
        return index / valuesPerChunk;
    }

    public int localX(int index) {
        checkIndex(index);
        return index % CHUNK_WIDTH;
    }

    public int localZ(int index) {
        checkIndex(index);
        return (index / CHUNK_WIDTH) % CHUNK_WIDTH;
    }

    public int localY(int index) {
        checkIndex(index);
        return (index % valuesPerChunk) / BLOCKS_PER_LAYER;
    }

    public int blockX(int index) {
        int chunkOffsetX = chunkIndex(index) % request.chunkCountX();
        return (request.firstChunkX() + chunkOffsetX) * CHUNK_WIDTH + localX(index);
    }

    public int blockY(int index) {
        return request.minBlockY() + localY(index);
    }

    public int blockZ(int index) {
        int chunkOffsetZ = chunkIndex(index) / request.chunkCountX();
        return (request.firstChunkZ() + chunkOffsetZ) * CHUNK_WIDTH + localZ(index);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= totalValues) {
            throw new IndexOutOfBoundsException("chunk output index " + index
                    + " outside 0.." + (totalValues - 1));
        }
    }
}
