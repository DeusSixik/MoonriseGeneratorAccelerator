package dev.sixik.generator_accelerator.common.worldgen.workspace;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GAChunkWorkspaceContextTest {
    @Test
    void bindRestoresPreviousWorkspace() {
        GAChunkWorkspace first = workspace(0, 0);
        GAChunkWorkspace second = workspace(1, 1);

        assertNull(GAChunkWorkspaceContext.current());
        try (GAChunkWorkspaceContext.Scope ignored = GAChunkWorkspaceContext.bind(first)) {
            assertSame(first, GAChunkWorkspaceContext.current());
            try (GAChunkWorkspaceContext.Scope ignoredNested = GAChunkWorkspaceContext.bind(second)) {
                assertSame(second, GAChunkWorkspaceContext.current());
            }
            assertSame(first, GAChunkWorkspaceContext.current());
        }
        assertNull(GAChunkWorkspaceContext.current());
    }

    private static GAChunkWorkspace workspace(int chunkX, int chunkZ) {
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(chunkX, chunkZ));
        when(chunk.getMinBuildHeight()).thenReturn(-64);
        when(chunk.getHeight()).thenReturn(384);
        when(chunk.getSectionsCount()).thenReturn(24);
        when(chunk.getMinSection()).thenReturn(-4);
        when(chunk.getMaxSection()).thenReturn(20);
        workspace.begin(chunk);
        return workspace;
    }
}
