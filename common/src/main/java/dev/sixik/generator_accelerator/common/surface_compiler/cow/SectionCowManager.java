package dev.sixik.generator_accelerator.common.surface_compiler.cow;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SectionCowManager {
    private final ChunkAccess chunk;

    private final int minBuildY;
    private final int chunkMinX;
    private final int chunkMinZ;
    private final Map<Integer, CowSectionWriter> writers = new LinkedHashMap<>();
    private boolean committed;
    private boolean discarded;

    public SectionCowManager(ChunkAccess chunk) {
        this.chunk = chunk;
        this.minBuildY = chunk.getMinBuildHeight();
        this.chunkMinX = chunk.getPos().getMinBlockX();
        this.chunkMinZ = chunk.getPos().getMinBlockZ();
    }

    public CowSectionWriter writerForSection(int sectionIndex) {
        if (this.committed || this.discarded) {
            throw new IllegalStateException("CoW manager is already closed");
        }
        return this.writers.computeIfAbsent(sectionIndex, index -> {
            LevelChunkSection section = this.chunk.getSection(index);
            int[] source = LevelChunkSection$FlatBlockArray.rawData(section);
            int[] copy = source == null ? null : Arrays.copyOf(source, source.length);
            return new CowSectionWriter(index, section, copy);
        });
    }

    public CowSectionWriter writerForY(int y) {
        return writerForSection((y - this.minBuildY) >> 4);
    }

    public boolean dirty() {
        for (CowSectionWriter writer : this.writers.values()) {
            if (writer.dirty()) {
                return true;
            }
        }
        return false;
    }

    public CowCommitPlan plan() {
        return new CowCommitPlan(this.writers.values().stream().filter(CowSectionWriter::dirty).toList());
    }

    public void commit() {
        if (this.discarded) {
            throw new IllegalStateException("cannot commit discarded CoW plan");
        }
        CowCommitPlan plan = plan();
        for (CowSectionWriter writer : plan.dirtySections()) {
            writer.commit(this.chunk, this.minBuildY, this.chunkMinX, this.chunkMinZ);
        }
        this.committed = true;
    }

    public void discard() {
        this.writers.clear();
        this.discarded = true;
    }

    public boolean committed() {
        return this.committed;
    }

    public boolean discarded() {
        return this.discarded;
    }

    static int localIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    static BlockPos sectionBlockPos(int minBuildY, int chunkMinX, int chunkMinZ, int sectionIndex, int localX, int localY, int localZ) {
        return new BlockPos(chunkMinX + localX, minBuildY + (sectionIndex << 4) + localY, chunkMinZ + localZ);
    }

    static int id(BlockState state) {
        return Block.getId(state);
    }

    static void publishWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        for (Heightmap.Types type : Heightmap.Types.values()) {
            chunk.getOrCreateHeightmapUnprimed(type).update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
        }
        if (!state.getFluidState().isEmpty()) {
            chunk.markPosForPostprocessing(pos);
        }
        GAWorkspaceWriteBridge.mirrorCurrent(chunk, pos, state);
    }
}
