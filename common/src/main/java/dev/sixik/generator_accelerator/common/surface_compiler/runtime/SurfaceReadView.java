package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public interface SurfaceReadView {
    BlockState getBlockState(int x, int y, int z);

    FluidState getFluidState(int x, int y, int z);
}
