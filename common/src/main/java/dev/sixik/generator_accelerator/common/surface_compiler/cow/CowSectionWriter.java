package dev.sixik.generator_accelerator.common.surface_compiler.cow;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceSectionWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class CowSectionWriter implements SurfaceSectionWriter {
    private final int sectionIndex;
    private final LevelChunkSection section;
    private final int[] rawCopy;
    private int[] changedLocalIndices;
    private BlockState[] changedStates;
    private int changedCount;
    private boolean dirty;

    CowSectionWriter(int sectionIndex, LevelChunkSection section, int[] rawCopy) {
        this.sectionIndex = sectionIndex;
        this.section = section;
        this.rawCopy = rawCopy;
    }

    @Override
    public void setBlockState(int localX, int localY, int localZ, BlockState state) {
        validateLocal(localX, localY, localZ);
        if (this.rawCopy != null) {
            this.rawCopy[SectionCowManager.localIndex(localX, localY, localZ)] = SectionCowManager.id(state);
        }
        recordChange(localX, localY, localZ, state);
        this.dirty = true;
    }

    @Override
    public boolean dirty() {
        return this.dirty;
    }

    public int sectionIndex() {
        return this.sectionIndex;
    }

    public int[] rawCopy() {
        return this.rawCopy;
    }

    void commit(ChunkAccess chunk, int minBuildY, int chunkMinX, int chunkMinZ) {
        if (!this.dirty) {
            return;
        }
        if (this.rawCopy != null && this.section instanceof LevelChunkSection$FlatBlockArray flatBlockArray) {
            if (!flatBlockArray.bts$copyRawBlockDataForGeneration(this.rawCopy)) {
                throw new IllegalStateException("failed to commit raw CoW section");
            }
        }
        for (int i = 0; i < this.changedCount; i++) {
            int localIndex = this.changedLocalIndices[i];
            int localX = localIndex & 15;
            int localZ = (localIndex >>> 4) & 15;
            int localY = (localIndex >>> 8) & 15;
            BlockState state = this.changedStates[i];
            if (this.rawCopy == null) {
                this.section.setBlockState(localX, localY, localZ, state, false);
            }
            BlockPos pos = SectionCowManager.sectionBlockPos(minBuildY, chunkMinX, chunkMinZ, this.sectionIndex, localX, localY, localZ);
            SectionCowManager.publishWrite(chunk, pos, state);
        }
    }

    private void recordChange(int localX, int localY, int localZ, BlockState state) {
        if (this.changedLocalIndices == null) {
            this.changedLocalIndices = new int[16];
            this.changedStates = new BlockState[16];
        } else if (this.changedCount == this.changedLocalIndices.length) {
            int newLength = this.changedLocalIndices.length << 1;
            this.changedLocalIndices = java.util.Arrays.copyOf(this.changedLocalIndices, newLength);
            this.changedStates = java.util.Arrays.copyOf(this.changedStates, newLength);
        }
        this.changedLocalIndices[this.changedCount] = SectionCowManager.localIndex(localX, localY, localZ);
        this.changedStates[this.changedCount] = state;
        this.changedCount++;
    }

    private static void validateLocal(int localX, int localY, int localZ) {
        if ((localX | localY | localZ) < 0 || localX > 15 || localY > 15 || localZ > 15) {
            throw new IndexOutOfBoundsException("local section coordinates must be in 0..15");
        }
    }
}
