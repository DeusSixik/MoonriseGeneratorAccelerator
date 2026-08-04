package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.GAWorldgenPipelineStatus;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitMetrics;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACrossChunkMailboxRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        GACommitMetrics.resetGlobal();
        GACrossChunkMailboxRuntime.resetForTests();
        GAWorkspaceWriteBridge.resetWorkspaceOnlyCircuitBreakerForTests();
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
        int[] raw = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        LevelChunkSection section = flatSection(raw);
        ChunkAccess chunk = chunk(section);
        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk);
        GAChunkWorkspace workspace = session.workspace();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        workspace.setBlockIdWorkspaceOnlyIfChanged(1, 2, 3, Block.getId(dirt));
        session.close();

        assertEquals(Block.getId(dirt), raw[(2 << 8) | (3 << 4) | 1]);
        verify(section, never()).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));
        assertFalse(workspace.isDirtySection(0));
        assertTrue(workspace.metrics().finalizeNanos() > 0L);
        assertTrue(workspace.metrics().repackNanos() > 0L);
        assertEquals(1L, metric(GACommitMetrics.snapshotGlobal(), "accepted"));
    }

    @Test
    void closeReplaysWorkspaceOnlySideEffectsAfterSuccessfulRepack() {
        int[] raw = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        LevelChunkSection section = flatSection(raw);
        ChunkAccess chunk = chunk(section);
        Heightmap heightmap = mock(Heightmap.class);
        ShortList[] postProcessing = new ShortList[1];
        when(chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)).thenReturn(heightmap);
        when(chunk.getPostProcessing()).thenReturn(postProcessing);
        int dirtId = Block.getId(Blocks.DIRT.defaultBlockState());

        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk);
        GAChunkWorkspace workspace = session.workspace();
        workspace.setBlockIdWorkspaceOnlyIfChanged(1, 2, 3, dirtId);
        workspace.recordHeightmapUpdate(Heightmap.Types.WORLD_SURFACE_WG, 1, 2, 3, dirtId);
        workspace.recordPostprocessMark(1, 2, 3);
        session.close();

        assertEquals(dirtId, raw[(2 << 8) | (3 << 4) | 1]);
        verify(heightmap).update(eq(1), eq(2), eq(3), eq(Blocks.DIRT.defaultBlockState()));
        assertNotNull(postProcessing[0]);
        assertEquals(1, postProcessing[0].size());
        assertEquals(ProtoChunk.packOffsetCoordinates(new BlockPos(1, 2, 3)), postProcessing[0].getShort(0));
        assertEquals(1L, metric("heightmapSideEffectUpdates"));
        assertEquals(1L, metric("postprocessSideEffectMarks"));
    }

    @Test
    void asyncWorkspaceFutureKeepsContextUntilInnerFutureCompletes() throws Exception {
        LevelChunkSection section = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        ChunkAccess chunk = chunk(section);
        CompletableFuture<ChunkAccess> inner = new CompletableFuture<>();
        AtomicReference<GAChunkWorkspace> captured = new AtomicReference<>();

        CompletableFuture<ChunkAccess> wrapped = GAChunkWorkspaceRuntime.withImportedWorkspaceFuture(chunk, () -> {
            captured.set(GAChunkWorkspaceContext.current());
            GAScheduler.supplyAsync(GAScheduler.Lane.WORKSPACE, () -> {
                captured.set(GAChunkWorkspaceContext.current());
                return null;
            }).join();
            return inner;
        });

        assertTrue(captured.get() != null && captured.get().active());
        assertEquals(1L, metric("contextBoundSessions"));
        assertEquals(0L, metric("finalizeNanos"));

        inner.complete(chunk);
        assertEquals(chunk, wrapped.get(10, TimeUnit.SECONDS));
        assertTrue(metric("finalizeNanos") > 0L);
        assertFalse(captured.get().active());
    }

    @Test
    void terrainWorkspaceFutureBindsWhenWorkspaceOnlyWritesAreEnabledByDefault() {
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.hasOnlyAir()).thenReturn(true);
        ChunkAccess chunk = chunk(section);
        AtomicReference<GAChunkWorkspace> captured = new AtomicReference<>();

        GAChunkWorkspaceRuntime.withTerrainWorkspaceFuture(chunk, () -> {
            captured.set(GAChunkWorkspaceContext.current());
            return CompletableFuture.completedFuture(chunk);
        }).join();

        assertNotNull(captured.get());
        assertEquals(1L, metric("terrainAirImports"));
        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void terrainOnlyWorkspaceFinalRepackRunsLocallyWithoutCommitBatch() {
        int[] raw = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        LevelChunkSection section = flatSection(raw);
        ChunkAccess chunk = chunk(section);
        int dirtId = Block.getId(Blocks.DIRT.defaultBlockState());

        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk);
        GAChunkWorkspace workspace = session.workspace();
        assertNotNull(workspace);
        assertTrue(workspace.writeTerrainBlockIdWorkspaceOnlySectionDirty(
                (2 << 8) | (3 << 4) | 1,
                0,
                dirtId
        ));
        workspace.metrics().addTerrainBlockWrites(1L);
        workspace.markTerrainFinalized();
        session.close();

        assertEquals(dirtId, raw[(2 << 8) | (3 << 4) | 1]);
        assertEquals(1L, metric("finalRepackLocalTerrainSections"));
        assertEquals(1L, metric("finalRepackTerrainSectionCopies"));
        assertEquals(0L, metric(GACommitMetrics.snapshotGlobal(), "accepted"));
        assertEquals(0L, metric("finalizeFailures"));
    }

    @Test
    void diagnosticsReportRuntimeCommitAfterWorkspaceOnlyRepack() {
        LevelChunkSection section = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        ChunkAccess chunk = chunk(section);
        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk);
        session.workspace().setBlockIdWorkspaceOnlyIfChanged(1, 2, 3, Block.getId(Blocks.DIRT.defaultBlockState()));
        session.close();

        @SuppressWarnings("unchecked")
        Map<String, Object> gates = (Map<String, Object>) GAWorldgenPipelineStatus.snapshot().get("runtimeGates");

        assertEquals(true, gates.get("workspaceFinalRepackCommitEngine"));
        assertEquals(true, gates.get("deterministicCommitRuntime"));
    }

    @Test
    void finalRepackRepairsAirOnlySectionAfterWorkspaceOnlyWrites() {
        int[] raw = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        AtomicBoolean repaired = new AtomicBoolean();
        LevelChunkSection section = mock(LevelChunkSection.class,
                withSettings().extraInterfaces(LevelChunkSection$FlatBlockArray.class));
        when(section.hasOnlyAir()).thenAnswer(ignored -> !repaired.get());
        when(((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData()).thenReturn(raw);
        when(((LevelChunkSection$FlatBlockArray) section).bts$setRawBlockStateForGeneration(anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    raw[invocation.getArgument(0)] = invocation.getArgument(1);
                    return true;
                });
        when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                .thenAnswer(invocation -> {
                    System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                    repaired.set(true);
                    return true;
                });

        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk(section));
        session.workspace().setBlockIdWorkspaceOnlyIfChanged(1, 2, 3, Block.getId(Blocks.DIRT.defaultBlockState()));
        session.close();

        assertTrue(repaired.get());
        assertEquals(Block.getId(Blocks.DIRT.defaultBlockState()), raw[(2 << 8) | (3 << 4) | 1]);
        assertEquals(1L, metric("finalRepackRepairs"));
        assertEquals(0L, metric("finalizeFailures"));
    }

    @Test
    void finalRepackFailureUsesEmergencyReplayBeforeDisablingWorkspaceOnly() {
        int[] raw = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        LevelChunkSection section = mock(LevelChunkSection.class,
                withSettings().extraInterfaces(LevelChunkSection$FlatBlockArray.class));
        when(section.hasOnlyAir()).thenReturn(false);
        when(((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData()).thenReturn(raw);
        when(((LevelChunkSection$FlatBlockArray) section).bts$setRawBlockStateForGeneration(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("simulated raw write failure"));
        when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                .thenAnswer(invocation -> {
                    System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                    return true;
                });

        GAChunkWorkspaceRuntime.Session session = GAChunkWorkspaceRuntime.acquireImported(chunk(section));
        session.workspace().setBlockIdWorkspaceOnlyIfChanged(1, 2, 3, Block.getId(Blocks.DIRT.defaultBlockState()));
        session.close();

        assertEquals(Block.getId(Blocks.DIRT.defaultBlockState()), raw[(2 << 8) | (3 << 4) | 1]);
        assertEquals(1L, metric("emergencyRepacks"));
        assertEquals(0L, metric("emergencyRepackFailures"));
        assertEquals(0L, metric("finalizeFailures"));
        assertFalse(GAWorkspaceWriteBridge.workspaceOnlyWritesRuntimeDisabled());
    }

    @Test
    void workspaceOnlyNeighborWriteDrainsThroughCrossChunkMailbox() {
        LevelChunkSection targetSection = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        ChunkAccess target = chunkAt(1, 0, targetSection);

        assertTrue(GACrossChunkMailboxRuntime.enqueueBlockWrite(
                0,
                0,
                16,
                2,
                3,
                Block.getId(Blocks.DIRT.defaultBlockState()),
                2
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> gatesBeforeDrain =
                (Map<String, Object>) GAWorldgenPipelineStatus.snapshot().get("runtimeGates");
        assertEquals(true, gatesBeforeDrain.get("crossChunkMailboxQueued"));
        assertEquals(false, gatesBeforeDrain.get("crossChunkMailboxRuntime"));

        GAChunkWorkspaceRuntime.Session targetSession = GAChunkWorkspaceRuntime.acquireImported(target);
        targetSession.close();

        verify(target).setBlockState(
                eq(new net.minecraft.core.BlockPos(16, 2, 3)),
                eq(Blocks.DIRT.defaultBlockState()),
                eq(false)
        );
        assertEquals(1L, metric(GACrossChunkMailboxRuntime.snapshot(), "drained"));

        @SuppressWarnings("unchecked")
        Map<String, Object> gatesAfterDrain =
                (Map<String, Object>) GAWorldgenPipelineStatus.snapshot().get("runtimeGates");
        assertEquals(true, gatesAfterDrain.get("crossChunkMailboxLiveDrains"));
        assertEquals(true, gatesAfterDrain.get("crossChunkMailboxRuntime"));
    }

    @Test
    void mailboxDrainCanRunWithoutWorkspaceSession() {
        LevelChunkSection targetSection = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        ChunkAccess target = chunkAt(1, 0, targetSection);

        assertTrue(GACrossChunkMailboxRuntime.enqueueBlockWrite(
                0,
                0,
                16,
                2,
                3,
                Block.getId(Blocks.DIRT.defaultBlockState()),
                2
        ));

        GAChunkWorkspaceRuntime.drainCrossChunkMailboxIfQueued(target);

        verify(target).setBlockState(
                eq(new net.minecraft.core.BlockPos(16, 2, 3)),
                eq(Blocks.DIRT.defaultBlockState()),
                eq(false)
        );
        assertEquals(1L, metric(GACrossChunkMailboxRuntime.snapshot(), "drained"));
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

    @Test
    void serverLifecycleResetClearsWorkspaceSessionState() {
        LevelChunkSection section = flatSection(new int[GAChunkWorkspace.BLOCKS_PER_SECTION]);
        ChunkAccess chunk = chunk(section);
        GAChunkWorkspace workspace = GAChunkWorkspacePool.acquire(chunk, false);
        assertNotNull(workspace);
        GAChunkWorkspacePool.release(workspace);
        assertTrue(((Number) GAChunkWorkspacePool.snapshot().get("pooled")).intValue() >= 1);

        assertTrue(GACrossChunkMailboxRuntime.enqueueBlockWrite(
                0,
                0,
                16,
                2,
                3,
                Block.getId(Blocks.DIRT.defaultBlockState()),
                2
        ));
        GAWorkspaceWriteBridge.disableWorkspaceOnlyWritesForSession("test disable", new IllegalStateException("boom"));
        GAChunkWorkspaceContext.Scope workspaceScope = GAChunkWorkspaceContext.bind(workspace);
        GADecorationJournalContext.Scope journalScope = GADecorationJournalContext.bind(new GADecorationWriteJournal());

        try {
            GAChunkWorkspaceRuntime.resetForServerLifecycle();

            assertEquals(0, ((Number) GAChunkWorkspacePool.snapshot().get("pooled")).intValue());
            assertEquals(0, GACrossChunkMailboxRuntime.queuedCommands());
            assertFalse(GAWorkspaceWriteBridge.workspaceOnlyWritesRuntimeDisabled());
            assertNull(GAChunkWorkspaceContext.current());
            assertNull(GADecorationJournalContext.current());
        } finally {
            journalScope.close();
            workspaceScope.close();
        }
    }

    private static long metric(String key) {
        return ((Number) GAChunkWorkspaceMetrics.snapshotGlobal().get(key)).longValue();
    }

    private static long metric(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private static ChunkAccess chunk(LevelChunkSection... sections) {
        return chunkAt(0, 0, sections);
    }

    private static ChunkAccess chunkAt(int chunkX, int chunkZ, LevelChunkSection... sections) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(chunkX, chunkZ));
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
        when(((LevelChunkSection$FlatBlockArray) section).bts$setRawBlockStateForGeneration(anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    raw[invocation.getArgument(0)] = invocation.getArgument(1);
                    return true;
                });
        when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                .thenAnswer(invocation -> {
                    System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                    return true;
                });
        return section;
    }
}
