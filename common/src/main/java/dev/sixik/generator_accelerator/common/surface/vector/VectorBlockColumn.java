package dev.sixik.generator_accelerator.common.surface.vector;

import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class VectorBlockColumn implements BlockColumn {

    private ChunkAccess pChunk;
    private LevelChunkSection[] sections;
    private BlockPos.MutableBlockPos columnPos;

    public VectorBlockColumn(ChunkAccess pChunk, LevelChunkSection[] sections, BlockPos.MutableBlockPos mutableBlockPos) {
        reset(pChunk, sections, mutableBlockPos);
    }

    public void reset(ChunkAccess pChunk, LevelChunkSection[] sections, BlockPos.MutableBlockPos mutableBlockPos) {
        this.pChunk = pChunk;
        this.sections = sections;
        this.columnPos = mutableBlockPos;
    }

    public void clear() {
        this.pChunk = null;
        this.sections = null;
        this.columnPos = null;
    }

    @Override
    public BlockState getBlock(int y) {
        int sectionIndex = this.pChunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunkSection section = this.sections[sectionIndex];
        if (section == null) {
            return Blocks.AIR.defaultBlockState();
        }

        int[] raw = ((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData();
        if (raw != null) {
            int localY = y & 15;
            int index = (localY << 8) | ((this.columnPos.getZ() & 15) << 4) | (this.columnPos.getX() & 15);
            return FastBlockStateCache.getBlockState(raw[index]);
        }
        return this.pChunk.getBlockState(this.columnPos.setY(y));
    }

    @Override
    public void setBlock(int y, BlockState state) {
        LevelHeightAccessor levelheightaccessor = this.pChunk.getHeightAccessorForGeneration();
        if (y >= levelheightaccessor.getMinBuildHeight() && y < levelheightaccessor.getMaxBuildHeight()) {
            this.pChunk.setBlockState(this.columnPos.setY(y), state, false);
            GAWorkspaceWriteBridge.mirrorCurrent(this.pChunk, this.columnPos, state);
            if (!state.getFluidState().isEmpty()) {
                this.pChunk.markPosForPostprocessing(this.columnPos);
            }
        }
    }
}
