package dev.sixik.generator_accelerator.common.worldgen.workspace;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void clearRetainedDropsPooledWorkspaces() {
        GAChunkWorkspacePool.clearRetained();
        GAChunkWorkspace workspace = GAChunkWorkspacePool.acquire(chunk(), false);
        assertNotNull(workspace);
        GAChunkWorkspacePool.release(workspace);

        assertTrue(((Number) GAChunkWorkspacePool.snapshot().get("pooled")).intValue() >= 1);

        GAChunkWorkspacePool.clearRetained();

        Map<String, Object> after = GAChunkWorkspacePool.snapshot();
        assertEquals(0, ((Number) after.get("pooled")).intValue());
        assertEquals(0L, ((Number) after.get("pooledEstimatedRetainedBytes")).longValue());
    }

    @Test
    void releaseDoesNotRetainMoreThanConfiguredPoolCap() {
        GAChunkWorkspacePool.clearRetained();
        Map<String, Object> before = GAChunkWorkspacePool.snapshot();
        int maxInFlight = ((Number) before.get("maxInFlight")).intValue();
        int maxRetained = ((Number) before.get("maxRetainedWorkspaces")).intValue();
        List<GAChunkWorkspace> acquired = new ArrayList<>();

        for (int i = 0; i < maxInFlight; i++) {
            GAChunkWorkspace workspace = GAChunkWorkspacePool.acquire(chunk(), false);
            assertNotNull(workspace);
            acquired.add(workspace);
        }

        for (GAChunkWorkspace workspace : acquired) {
            GAChunkWorkspacePool.release(workspace);
        }

        Map<String, Object> after = GAChunkWorkspacePool.snapshot();
        assertTrue(((Number) after.get("pooled")).intValue() <= maxRetained);
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
