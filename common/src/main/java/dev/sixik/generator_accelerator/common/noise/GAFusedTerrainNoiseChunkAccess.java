package dev.sixik.generator_accelerator.common.noise;

import net.minecraft.world.level.block.state.BlockState;

public interface GAFusedTerrainNoiseChunkAccess {
    int GA_FALLBACK_BLOCK_ID = Integer.MIN_VALUE;
    long GA_FALLBACK_PACKED_BLOCK_ID = 1L << 33;
    int GA_PACKED_SCHEDULE_SHIFT = 32;

    boolean ga$fusedTerrainAvailable();

    int ga$sampleFusedTerrainBlockId(int defaultBlockId);

    default int ga$sampleFusedTerrainBlockId(int defaultBlockId, int blockX, int blockY, int blockZ) {
        return ga$sampleFusedTerrainBlockId(defaultBlockId);
    }

    default long ga$sampleFusedTerrainPackedBlockId(int defaultBlockId, int blockX, int blockY, int blockZ) {
        int blockId = ga$sampleFusedTerrainBlockId(defaultBlockId, blockX, blockY, blockZ);
        return blockId == GA_FALLBACK_BLOCK_ID ? GA_FALLBACK_PACKED_BLOCK_ID : ga$packFusedTerrain(blockId, false);
    }

    BlockState ga$sampleFusedTerrainBlockState(BlockState defaultBlock);

    static long ga$packFusedTerrain(int blockId, boolean scheduleFluidUpdate) {
        return (blockId & 0xFFFF_FFFFL) | (scheduleFluidUpdate ? (1L << GA_PACKED_SCHEDULE_SHIFT) : 0L);
    }

    static int ga$packedBlockId(long packed) {
        return (int) packed;
    }

    static boolean ga$packedScheduleFluidUpdate(long packed) {
        return (packed >>> GA_PACKED_SCHEDULE_SHIFT & 1L) != 0L;
    }

    static boolean ga$packedFallback(long packed) {
        return (packed & GA_FALLBACK_PACKED_BLOCK_ID) != 0L;
    }
}
