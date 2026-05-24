package dev.sixik.generator_accelerator_dod.api;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public interface DOD$BlockGetter {

    BlockState getBlockState(int x, int y, int z);

    BlockState getBlockState(long pos);

    int getBlockStateId(int x, int y, int z);

    int getBlockStateId(long pos);

    BlockEntity getBlockEntity(int x, int y, int z);

    BlockEntity getBlockEntity(long pos);

}
