package dev.sixik.generator_accelerator.common.surface_compiler.snapshot;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.Arrays;

public final class SectionCopySnapshot implements SurfaceReadSnapshot {
    private final int[] raw;
    private final LevelChunkSection fallback;

    public SectionCopySnapshot(LevelChunkSection section) {
        int[] rawData = section == null ? null : LevelChunkSection$FlatBlockArray.rawData(section);
        this.raw = rawData == null ? null : Arrays.copyOf(rawData, rawData.length);
        this.fallback = section;
    }

    @Override
    public boolean available() {
        return this.raw != null || this.fallback != null;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        if (this.raw != null) {
            return Block.stateById(this.raw[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]);
        }
        return this.fallback == null ? Blocks.VOID_AIR.defaultBlockState() : this.fallback.getBlockState(x & 15, y & 15, z & 15);
    }

    @Override
    public FluidState getFluidState(int x, int y, int z) {
        return getBlockState(x, y, z).getFluidState();
    }
}
