package dev.sixik.generator_accelerator.common.surface_compiler.snapshot;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

public final class SectionBorrowSnapshot implements SurfaceReadSnapshot {
    private final LevelChunkSection section;

    public SectionBorrowSnapshot(LevelChunkSection section) {
        this.section = section;
    }

    @Override
    public boolean available() {
        return this.section != null;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        return this.section == null ? Blocks.VOID_AIR.defaultBlockState() : this.section.getBlockState(x & 15, y & 15, z & 15);
    }

    @Override
    public FluidState getFluidState(int x, int y, int z) {
        return getBlockState(x, y, z).getFluidState();
    }
}
