package dev.sixik.generator_accelerator.common.surface_compiler.snapshot;

import dev.sixik.generator_accelerator.common.surface_compiler.halo.NonBlockingNeighborView;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class HaloSnapshot implements SurfaceReadSnapshot {
    private final NonBlockingNeighborView view;

    public HaloSnapshot(NonBlockingNeighborView view) {
        this.view = view;
    }

    @Override
    public boolean available() {
        return this.view != null && this.view.available();
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        return this.view.getBlockState(x, y, z);
    }

    @Override
    public FluidState getFluidState(int x, int y, int z) {
        return this.view.getFluidState(x, y, z);
    }
}
