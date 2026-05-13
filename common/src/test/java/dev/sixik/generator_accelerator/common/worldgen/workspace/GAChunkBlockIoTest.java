package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class GAChunkBlockIoTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void importToWorkspaceCopiesChunkSectionsThroughSafeSectionReads() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        LevelChunkSection filled = mock(LevelChunkSection.class);
        when(filled.hasOnlyAir()).thenReturn(false);
        when(filled.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        LevelChunkSection air = mock(LevelChunkSection.class);
        when(air.hasOnlyAir()).thenReturn(true);

        GAChunkWorkspace workspace = new GAChunkWorkspace();

        long imported = GAChunkBlockIo.importToWorkspace(chunk(filled, air), workspace);

        assertEquals(2L * GAChunkWorkspace.BLOCKS_PER_SECTION, imported);
        assertTrue(workspace.imported());
        assertTrue(workspace.blockBufferEnabled());
        assertEquals(Block.getId(stone), workspace.blockId(3, 4, 5));
        assertEquals(Block.getId(Blocks.AIR.defaultBlockState()), workspace.blockId(3, 20, 5));
        assertFalse(workspace.isDirtySection(0));
        assertFalse(workspace.isDirtySection(1));
    }

    @Test
    void importToWorkspaceUsesRawFlatBlockArrayWithoutSectionStateReads() {
        int[] raw = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        raw[(4 << 8) | (5 << 4) | 3] = Block.getId(stone);
        raw[(9 << 8) | (2 << 4) | 7] = Block.getId(water);
        LevelChunkSection section = flatSection(raw);

        GAChunkWorkspace workspace = new GAChunkWorkspace();

        long imported = GAChunkBlockIo.importToWorkspace(chunk(section), workspace);

        assertEquals(GAChunkWorkspace.BLOCKS_PER_SECTION, imported);
        assertEquals(Block.getId(stone), workspace.blockId(3, 4, 5));
        assertEquals(Block.getId(water), workspace.blockId(7, 9, 2));
        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void repackDirtySectionsWritesOnlyDirtyBlockRunsThroughSafeSectionWrites() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        LevelChunkSection first = mock(LevelChunkSection.class);
        when(first.hasOnlyAir()).thenReturn(false);
        when(first.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(air);
        LevelChunkSection second = mock(LevelChunkSection.class);
        when(second.hasOnlyAir()).thenReturn(true);

        ChunkAccess chunk = chunk(first, second);
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        GAChunkBlockIo.importToWorkspace(chunk, workspace);
        workspace.setBlockId(1, 2, 3, Block.getId(dirt));

        long written = GAChunkBlockIo.repackDirtySections(chunk, workspace);

        assertEquals(1L, written);
        verify(first).setBlockState(eq(1), eq(2), eq(3), eq(dirt), eq(false));
        verify(second, never()).setBlockState(anyInt(), anyInt(), anyInt(), eq(dirt), eq(false));
        assertFalse(workspace.isDirtySection(0));
        assertFalse(workspace.isDirtySection(1));
    }

    @Test
    void repackDirtySectionsWritesOnlyDirtyDiffsWithFastCacheStatesAndNoUpdateFlag() {
        LevelChunkSection section = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        ChunkAccess chunk = chunk(section);
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        GAChunkBlockIo.importToWorkspace(chunk, workspace);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        workspace.setBlockId(1, 2, 3, Block.getId(air));
        workspace.setBlockId(4, 5, 6, Block.getId(water));
        workspace.setBlockId(7, 8, 9, Block.getId(stone));

        long written = GAChunkBlockIo.repackDirtySections(chunk, workspace);

        assertEquals(3L, written);
        verify(section, times(3))
                .setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));
        verify(section).setBlockState(eq(1), eq(2), eq(3), eq(air), eq(false));
        verify(section).setBlockState(eq(4), eq(5), eq(6), eq(water), eq(false));
        verify(section).setBlockState(eq(7), eq(8), eq(9), eq(stone), eq(false));
        verify(section, never()).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(true));
        assertFalse(workspace.isDirtySection(0));
    }

    @Test
    void repackDirtySectionsUsesFullRawCopyForDenseWorkspaceDiffs() {
        int[] raw = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        LevelChunkSection section = flatSection(raw);
        when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                .thenAnswer(invocation -> {
                    System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                    return true;
                });
        ChunkAccess chunk = chunk(section);
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        GAChunkBlockIo.importToWorkspace(chunk, workspace);
        int dirtId = Block.getId(Blocks.DIRT.defaultBlockState());

        for (int index = 0; index < 1024; index++) {
            workspace.writeTerrainBlockIdWorkspaceOnly(index, 0, index & 255, index >>> 8, dirtId);
        }

        long written = GAChunkBlockIo.repackDirtySections(chunk, workspace);

        assertEquals(GAChunkWorkspace.BLOCKS_PER_SECTION, written);
        assertEquals(dirtId, raw[0]);
        assertEquals(dirtId, raw[1023]);
        assertFalse(workspace.isDirtySection(0));
        verify((LevelChunkSection$FlatBlockArray) section, times(1)).bts$copyRawBlockDataForGeneration(any(int[].class));
        verify((LevelChunkSection$FlatBlockArray) section, never()).bts$setRawBlockStateForGeneration(anyInt(), anyInt());
    }

    private static ChunkAccess chunk(LevelChunkSection... sections) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        when(chunk.getMinBuildHeight()).thenReturn(0);
        when(chunk.getHeight()).thenReturn(sections.length * GAChunkWorkspace.CHUNK_WIDTH);
        when(chunk.getSectionsCount()).thenReturn(sections.length);
        when(chunk.getMinSection()).thenReturn(0);
        when(chunk.getMaxSection()).thenReturn(sections.length);
        when(chunk.getSections()).thenReturn(sections);
        return chunk;
    }

    private static LevelChunkSection flatSection(int[] raw) {
        LevelChunkSection section = mock(LevelChunkSection.class,
                withSettings().extraInterfaces(LevelChunkSection$FlatBlockArray.class));
        when(section.hasOnlyAir()).thenReturn(false);
        when(((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData()).thenReturn(raw);
        return section;
    }
}
