package dev.sixik.generator_accelerator.common.surface_compiler.snapshot;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceReadView;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public interface SurfaceReadSnapshot extends SurfaceReadView {
    boolean available();

    @Override
    BlockState getBlockState(int x, int y, int z);

    @Override
    FluidState getFluidState(int x, int y, int z);
}
