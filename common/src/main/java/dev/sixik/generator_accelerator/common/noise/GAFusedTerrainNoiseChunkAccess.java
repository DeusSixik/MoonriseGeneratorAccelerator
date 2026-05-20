package dev.sixik.generator_accelerator.common.noise;

import net.minecraft.world.level.block.state.BlockState;

public interface GAFusedTerrainNoiseChunkAccess {
    int GA_FALLBACK_BLOCK_ID = Integer.MIN_VALUE;

    boolean ga$fusedTerrainAvailable();

    int ga$sampleFusedTerrainBlockId(int defaultBlockId);

    BlockState ga$sampleFusedTerrainBlockState(BlockState defaultBlock);
}
