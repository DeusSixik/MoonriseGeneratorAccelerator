package dev.sixik.generator_accelerator.common.worldgen.scheduler;

public record GAChunkWorkKey(int worldId, int chunkX, int chunkZ, byte statusId) {
    public static final byte STATUS_LEGACY_BOUNDARY = -1;

    public GAChunkWorkKey {
        if (worldId < 0) {
            throw new IllegalArgumentException("worldId must be non-negative");
        }
    }
}
