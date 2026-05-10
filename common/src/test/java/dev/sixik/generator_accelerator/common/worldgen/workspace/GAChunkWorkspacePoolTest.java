package dev.sixik.generator_accelerator.common.worldgen.workspace;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GAChunkWorkspacePoolTest {
    @Test
    void acquireImportsMetadataAndReleaseReturnsPermit() {
        GAChunkWorkspacePool.resetMetrics();

        GAChunkWorkspace workspace = GAChunkWorkspacePool.acquire(chunk(), false);
        assertNotNull(workspace);
        assertTrue(workspace.imported());

        Map<String, Object> during = GAChunkWorkspacePool.snapshot();
        assertTrue(((Number) during.get("inFlight")).intValue() >= 1);

        GAChunkWorkspacePool.release(workspace);

        Map<String, Object> after = GAChunkWorkspacePool.snapshot();
        assertTrue(((Number) after.get("released")).longValue() >= 1L);
        assertTrue(((Number) after.get("pooled")).intValue() >= 1);
    }

    private static ChunkAccess chunk() {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(1, 2));
        when(chunk.getMinBuildHeight()).thenReturn(-64);
        when(chunk.getHeight()).thenReturn(384);
        when(chunk.getSectionsCount()).thenReturn(24);
        when(chunk.getMinSection()).thenReturn(-4);
        when(chunk.getMaxSection()).thenReturn(20);
        return chunk;
    }
}
