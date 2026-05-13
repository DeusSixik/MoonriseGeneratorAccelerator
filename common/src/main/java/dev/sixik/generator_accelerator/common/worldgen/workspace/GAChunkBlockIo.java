package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;
import java.util.Objects;

/** Safe ChunkAccess adapter for detached workspace block-id import/repack. */
public final class GAChunkBlockIo {
    private GAChunkBlockIo() {
    }

    public static long importToWorkspace(ChunkAccess chunk, GAChunkWorkspace workspace) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        if (!workspace.imported()) {
            workspace.begin(chunk, true);
        } else {
            validateSameChunk(chunk, workspace);
        }

        long start = System.nanoTime();
        workspace.ensureBlockBufferCapacity(workspace.blockCount());
        int[] blocks = workspace.blockIds();
        LevelChunkSection[] sections = chunk.getSections();
        int importedBlocks;
        if (isSectionAligned(workspace)) {
            importedBlocks = importAlignedSections(workspace, sections, blocks);
        } else {
            importedBlocks = importGeneric(workspace, sections, blocks);
        }
        workspace.clearCommittedBlockDirties();
        workspace.metrics().addImportNanos(System.nanoTime() - start);
        workspace.metrics();
        return importedBlocks;
    }

    private static int importAlignedSections(GAChunkWorkspace workspace, LevelChunkSection[] sections, int[] blocks) {
        int airId = airId();
        int importedBlocks = 0;
        for (int sectionIndex = 0; sectionIndex < workspace.sectionCount(); sectionIndex++) {
            int baseIndex = sectionIndex * GAChunkWorkspace.BLOCKS_PER_SECTION;
            LevelChunkSection section = sectionAt(sections, sectionIndex);
            if (section == null || section.hasOnlyAir()) {
                Arrays.fill(blocks, baseIndex, baseIndex + GAChunkWorkspace.BLOCKS_PER_SECTION, airId);
                importedBlocks += GAChunkWorkspace.BLOCKS_PER_SECTION;
                continue;
            }
            int[] raw = LevelChunkSection$FlatBlockArray.rawData(section);
            if (raw != null) {
                System.arraycopy(raw, 0, blocks, baseIndex, GAChunkWorkspace.BLOCKS_PER_SECTION);
                importedBlocks += GAChunkWorkspace.BLOCKS_PER_SECTION;
                continue;
            }
            for (int localY = 0; localY < GAChunkWorkspace.CHUNK_WIDTH; localY++) {
                int rowBase = baseIndex | (localY << 8);
                for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                    int rowIndex = rowBase | (localZ << 4);
                    for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                        blocks[rowIndex | localX] = stateId(section.getBlockState(localX, localY, localZ));
                        importedBlocks++;
                    }
                }
            }
        }
        return importedBlocks;
    }

    private static int importGeneric(GAChunkWorkspace workspace, LevelChunkSection[] sections, int[] blocks) {
        int airId = airId();
        int importedBlocks = 0;
        int index = 0;
        int maxY = workspace.minBuildHeight() + workspace.buildHeight();
        for (int y = workspace.minBuildHeight(); y < maxY; y++) {
            int sectionIndex = sectionIndexForY(workspace, y);
            LevelChunkSection section = sectionAt(sections, sectionIndex);
            boolean airSection = section == null || section.hasOnlyAir();
            int[] raw = airSection ? null : LevelChunkSection$FlatBlockArray.rawData(section);
            int localY = y & 15;
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    blocks[index++] = airSection
                            ? airId
                            : raw == null
                                    ? stateId(section.getBlockState(localX, localY, localZ))
                                    : raw[(localY << 8) | (localZ << 4) | localX];
                    importedBlocks++;
                }
            }
        }
        return importedBlocks;
    }

    public static long repackDirtySections(ChunkAccess chunk, GAChunkWorkspace workspace) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        validateSameChunk(chunk, workspace);
        LevelChunkSection[] sections = chunk.getSections();

        return workspace.repackDirtyBlockIds((localX, y, localZ, blockId) -> {
            int sectionIndex = sectionIndexForY(workspace, y);
            LevelChunkSection section = sectionAt(sections, sectionIndex);
            if (section == null) {
                throw new IllegalStateException("missing section " + sectionIndex + " for y=" + y);
            }
            section.setBlockState(localX, y & 15, localZ, FastBlockStateCache.getBlockState(blockId), false);
        });
    }

    public static long repackDirtySection(ChunkAccess chunk, GAChunkWorkspace workspace, int sectionIndex) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        validateSameChunk(chunk, workspace);
        LevelChunkSection[] sections = chunk.getSections();
        LevelChunkSection section = sectionAt(sections, sectionIndex);
        if (section == null) {
            throw new IllegalStateException("missing section " + sectionIndex);
        }

        LevelChunkSection$FlatBlockArray flat = section instanceof LevelChunkSection$FlatBlockArray array ? array : null;
        int[] raw = flat == null ? null : flat.bts$getRawBlockData();
        if (raw != null) {
            return workspace.repackDirtyBlockSection(sectionIndex, (localX, y, localZ, blockId) -> {
                int index = ((y & 15) << 8) | (localZ << 4) | localX;
                if (!flat.bts$setRawBlockStateForGeneration(index, blockId)) {
                    section.setBlockState(localX, y & 15, localZ, FastBlockStateCache.getBlockState(blockId), false);
                }
            });
        }
        return workspace.repackDirtyBlockSection(sectionIndex, (localX, y, localZ, blockId) ->
                section.setBlockState(localX, y & 15, localZ, FastBlockStateCache.getBlockState(blockId), false));
    }

    private static int stateId(BlockState state) {
        return GA$BlockStateExtension.get(state).bts$getFastId();
    }

    private static int airId() {
        return Block.getId(Blocks.AIR.defaultBlockState());
    }

    private static LevelChunkSection sectionAt(LevelChunkSection[] sections, int sectionIndex) {
        return sectionIndex < 0 || sectionIndex >= sections.length ? null : sections[sectionIndex];
    }

    private static int sectionIndexForY(GAChunkWorkspace workspace, int y) {
        return Math.floorDiv(y, GAChunkWorkspace.CHUNK_WIDTH) - workspace.minSectionY();
    }

    private static boolean isSectionAligned(GAChunkWorkspace workspace) {
        return workspace.minBuildHeight() == workspace.minSectionY() * GAChunkWorkspace.CHUNK_WIDTH
                && workspace.buildHeight() == workspace.sectionCount() * GAChunkWorkspace.CHUNK_WIDTH;
    }

    private static void validateSameChunk(ChunkAccess chunk, GAChunkWorkspace workspace) {
        if (!workspace.imported()) {
            throw new IllegalStateException("workspace metadata is not imported");
        }
        if (chunk.getPos().x != workspace.chunkX() || chunk.getPos().z != workspace.chunkZ()) {
            throw new IllegalArgumentException("chunk does not match workspace metadata");
        }
        if (chunk.getMinBuildHeight() != workspace.minBuildHeight() || chunk.getHeight() != workspace.buildHeight()) {
            throw new IllegalArgumentException("chunk height does not match workspace metadata");
        }
    }
}
