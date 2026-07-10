package dev.sixik.generator_accelerator.common.surface_compiler.halo;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceReadView;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class NonBlockingNeighborView implements SurfaceReadView {
    private final SurfaceReadView local;
    private final boolean available;

    public NonBlockingNeighborView(SurfaceReadView local, boolean available) {
        this.local = local;
        this.available = available;
    }

    public boolean available() {
        return this.available;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        if (!this.available) {
            throw new IllegalStateException("halo unavailable in non-blocking mode");
        }
        return this.local.getBlockState(x, y, z);
    }

    @Override
    public FluidState getFluidState(int x, int y, int z) {
        if (!this.available) {
            throw new IllegalStateException("halo unavailable in non-blocking mode");
        }
        return this.local.getFluidState(x, y, z);
    }
}
