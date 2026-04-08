package dev.sixik.generator_accelerator.common.surface.vector;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class VectorBlockColumn implements BlockColumn {

    private final ChunkAccess pChunk;
    private final LevelChunkSection[] sections;
    private final BlockPos.MutableBlockPos columnPos;

    public VectorBlockColumn(ChunkAccess pChunk, LevelChunkSection[] sections, BlockPos.MutableBlockPos mutableBlockPos) {
        this.pChunk = pChunk;
        this.sections = sections;
        this.columnPos = mutableBlockPos;
    }

    @Override
    public BlockState getBlock(int y) {
        int sectionIndex = pChunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= sections.length) return Blocks.AIR.defaultBlockState();
        LevelChunkSection section = sections[sectionIndex];
        if (section == null) return Blocks.AIR.defaultBlockState();

        int[] raw = ((LevelChunkSection$FlatBlockArray)section).bts$getRawBlockData();
        if (raw != null) {
            int localY = y & 15;
            int index = (localY << 8) | ((columnPos.getZ() & 15) << 4) | (columnPos.getX() & 15);
            return Block.stateById(raw[index]);
        }
        return pChunk.getBlockState(columnPos.setY(y));
    }

    @Override
    public void setBlock(int y, BlockState state) {
        LevelHeightAccessor levelheightaccessor = pChunk.getHeightAccessorForGeneration();
        if (y >= levelheightaccessor.getMinBuildHeight() && y < levelheightaccessor.getMaxBuildHeight()) {
            int sectionIndex = pChunk.getSectionIndex(y);
            LevelChunkSection section = sections[sectionIndex];
            if (section == null) return;

            int[] raw = ((LevelChunkSection$FlatBlockArray)section).bts$getRawBlockData();
            if (raw != null) {
                int localY = y & 15;
                int index = (localY << 8) | ((columnPos.getZ() & 15) << 4) | (columnPos.getX() & 15);

                // Ручное обновление счетчика (чтобы DOD-движок не пропустил секцию)
                BlockState oldState = Block.stateById(raw[index]);
                if (!oldState.isAir() && state.isAir()) section.nonEmptyBlockCount--;
                if (oldState.isAir() && !state.isAir()) section.nonEmptyBlockCount++;

                raw[index] = Block.getId(state);

                if (!state.getFluidState().isEmpty()) {
                    pChunk.markPosForPostprocessing(columnPos.setY(y));
                }
            } else {
                pChunk.setBlockState(columnPos.setY(y), state, false);
            }
        }
    }
}
