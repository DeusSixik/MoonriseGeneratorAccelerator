package dev.sixik.generator_accelerator.common.worldgen.commit;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GACrossChunkMailboxRuntimeTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        GACrossChunkMailboxRuntime.resetForTests();
    }

    @Test
    void snapshotReportsQueuedDrainedAndCollisionDiagnostics() {
        GACrossChunkMailboxRuntime.resetForTests();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        ChunkAccess target = targetChunk(1, 0);

        GACrossChunkMailboxRuntime.enqueueBlockWrite(0, 0, 16, 64, 0, Block.getId(stone), 2);
        GACrossChunkMailboxRuntime.enqueueBlockWrite(0, 0, 16, 64, 0, Block.getId(dirt), 2);

        Map<String, Object> queued = GACrossChunkMailboxRuntime.snapshot();
        assertEquals(2L, queued.get("attempted"));
        assertEquals(2L, queued.get("enqueued"));
        assertEquals(2, queued.get("queuedCommands"));
        assertEquals(1, queued.get("targetChunks"));
        assertEquals(2L, queued.get("maxQueueDepth"));
        assertEquals(1L, queued.get("maxTargetChunks"));

        GACrossChunkMailboxRuntime.drainBlockWrites(target);

        Map<String, Object> drained = GACrossChunkMailboxRuntime.snapshot();
        assertEquals(1L, drained.get("drained"));
        assertEquals(1L, drained.get("drainRejected"));
        assertEquals(1L, drained.get("drainCollisions"));
        assertEquals(1L, drained.get("drainExecutions"));
        assertEquals(0, drained.get("queuedCommands"));
        verify(target).setBlockState(any(BlockPos.class), eq(dirt), eq(false));
    }

    @Test
    void fallbackRatioTracksRejectedNeighborCommands() {
        GACrossChunkMailboxRuntime.resetForTests();

        GACrossChunkMailboxRuntime.enqueueBlockWrite(0, 0, 1, 64, 1, Block.getId(Blocks.STONE.defaultBlockState()), 2);

        Map<String, Object> snapshot = GACrossChunkMailboxRuntime.snapshot();
        assertEquals(1L, snapshot.get("attempted"));
        assertEquals(1L, snapshot.get("rejected"));
        assertEquals(1.0D, (double) snapshot.get("fallbackRatio"));
    }

    private static ChunkAccess targetChunk(int x, int z) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(x, z));
        return chunk;
    }
}
