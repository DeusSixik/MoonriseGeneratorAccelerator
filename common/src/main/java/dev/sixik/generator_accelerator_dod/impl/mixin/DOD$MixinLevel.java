package dev.sixik.generator_accelerator_dod.impl.mixin;

import dev.sixik.generator_accelerator_dod.api.DOD$BlockGetter;
import dev.sixik.generator_accelerator_dod.api.DOD$BlockWriter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
public class DOD$MixinLevel implements DOD$BlockGetter, DOD$BlockWriter {


    @Override
    public BlockState getBlockState(int x, int y, int z) {
        return null;
    }

    @Override
    public BlockState getBlockState(long pos) {
        return null;
    }

    @Override
    public int getBlockStateId(int x, int y, int z) {
        return 0;
    }

    @Override
    public int getBlockStateId(long pos) {
        return 0;
    }

    @Override
    public BlockEntity getBlockEntity(int x, int y, int z) {
        return null;
    }

    @Override
    public BlockEntity getBlockEntity(long pos) {
        return null;
    }

    @Override
    public void setBlockState(int x, int y, int z, int stateId) {

    }

    @Override
    public void setBlockState(long pos, int stateId) {

    }

    @Override
    public void setBlockState(int x, int y, int z, BlockState state) {

    }

    @Override
    public void setBlockState(long pos, BlockState state) {

    }
}
