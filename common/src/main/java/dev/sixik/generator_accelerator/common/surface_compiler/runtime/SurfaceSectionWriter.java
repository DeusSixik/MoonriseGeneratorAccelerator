package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

import net.minecraft.world.level.block.state.BlockState;

public interface SurfaceSectionWriter {
    void setBlockState(int localX, int localY, int localZ, BlockState state);

    boolean dirty();
}
