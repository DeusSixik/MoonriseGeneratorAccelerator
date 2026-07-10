package dev.sixik.generator_accelerator.common.surface_compiler.snapshot;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class ColumnBandSnapshot implements SurfaceReadSnapshot {
    private final BlockState[] column = new BlockState[384];
    private final int minY;
    private boolean populated;

    public ColumnBandSnapshot(int minY) {
        this.minY = minY;
    }

    public void set(int y, BlockState state) {
        int index = y - this.minY;
        if (index >= 0 && index < this.column.length) {
            this.column[index] = state;
            this.populated = true;
        }
    }

    @Override
    public boolean available() {
        return this.populated;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        int index = y - this.minY;
        if (index >= 0 && index < this.column.length && this.column[index] != null) {
            return this.column[index];
        }
        return Blocks.VOID_AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(int x, int y, int z) {
        return getBlockState(x, y, z).getFluidState();
    }
}
