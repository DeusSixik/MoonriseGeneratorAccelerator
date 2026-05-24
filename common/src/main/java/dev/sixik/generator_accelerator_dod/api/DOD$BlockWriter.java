package dev.sixik.generator_accelerator_dod.api;

import net.minecraft.world.level.block.state.BlockState;

public interface DOD$BlockWriter {

    void setBlockState(int x, int y, int z, int stateId);

    void setBlockState(long pos, int stateId);

    void setBlockState(int x, int y, int z, BlockState state);

    void setBlockState(long pos, BlockState state);
}
