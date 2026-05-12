package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class GAChunkWorkspaceRuntimeTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void resetMetrics() {
        GAScheduler.shutdownForTests();
        GAChunkWorkspaceMetrics.resetGlobal();
    }

    @AfterEach
    void shutdownScheduler() {
        GAScheduler.shutdownForTests();
    }

    @Test
    void acquireImportedCopiesBlocksOnWorkspaceLaneAndCloseReleases() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.hasOnlyAir()).thenReturn(false);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(stone);

        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk(section));
        GAChunkWorkspace workspace = session.workspace();

        assertTrue(session.active());
        assertNotNull(workspace);
        assertTrue(workspace.active());
        assertTrue(workspace.blockBufferEnabled());
        assertEquals(Block.getId(stone), workspace.blockId(1, 2, 3));

        session.close();

        assertFalse(workspace.active());
    }

    @Test
    void closeRepackDirtySectionsOnCommitLaneAndRecordsFinalizeMetrics() {
        LevelChunkSection section = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        ChunkAccess chunk = chunk(section);
        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk);
        GAChunkWorkspace workspace = session.workspace();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        workspace.setBlockId(1, 2, 3, Block.getId(dirt));
        session.close();

        verify(section).setBlockState(eq(1), eq(2), eq(3), eq(dirt), eq(false));
        verify(section, org.mockito.Mockito.times(GAChunkWorkspace.BLOCKS_PER_SECTION))
                .setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));
        assertFalse(workspace.isDirtySection(0));
        assertTrue(workspace.metrics().finalizeNanos() > 0L);
        assertTrue(workspace.metrics().repackNanos() > 0L);
    }

    @Test
    void closeSkipsRepackWhenWorkspaceHasNoDirtySections() {
        LevelChunkSection section = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk(section));
        GAChunkWorkspace workspace = session.workspace();

        session.close();

        verify(section, never()).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));
        assertTrue(workspace.metrics().finalizeNanos() > 0L);
        assertEquals(0L, workspace.metrics().repackNanos());
    }

    @Test
    void importFailureReturnsEmptySessionAndIncrementsFailureMetric() {
        ChunkAccess chunk = chunk(mock(LevelChunkSection.class));
        when(chunk.getSections()).thenThrow(new IllegalStateException("boom"));

        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk);

        assertFalse(session.active());
        assertNull(session.workspace());
        assertEquals(1L, metric("importFailures"));
        session.close();
    }

    private static long metric(String key) {
        return ((Number) GAChunkWorkspaceMetrics.snapshotGlobal().get(key)).longValue();
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
