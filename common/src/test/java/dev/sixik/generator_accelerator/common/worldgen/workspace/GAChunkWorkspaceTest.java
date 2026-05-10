package dev.sixik.generator_accelerator.common.worldgen.workspace;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GAChunkWorkspaceTest {
    @Test
    void beginImportsChunkMetadataWithoutAllocatingBlocksByDefault() {
        GAChunkWorkspace workspace = new GAChunkWorkspace();

        workspace.begin(chunk(3, -5, -64, 384, 24, -4, 20));

        assertTrue(workspace.active());
        assertTrue(workspace.imported());
        assertEquals(3, workspace.chunkX());
        assertEquals(-5, workspace.chunkZ());
        assertEquals(48, workspace.minBlockX());
        assertEquals(-80, workspace.minBlockZ());
        assertEquals(-64, workspace.minBuildHeight());
        assertEquals(384, workspace.buildHeight());
        assertEquals(24, workspace.sectionCount());
        assertEquals(-4, workspace.minSectionY());
        assertEquals(20, workspace.maxSectionY());
        assertFalse(workspace.blockBufferEnabled());
        assertEquals(0, workspace.blockCapacity());
        assertNull(workspace.blockIds());
        assertEquals(GAChunkWorkspace.UNKNOWN_HEIGHT, workspace.heightCandidate(0, 0));
        assertTrue(workspace.estimatedRetainedBytes() > 0L);
    }

    @Test
    void optionalBlockBufferIsFlatAndMarksDirtyMasks() {
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        workspace.begin(chunk(0, 0, -64, 384, 24, -4, 20), true);

        assertTrue(workspace.blockBufferEnabled());
        assertEquals(24 * GAChunkWorkspace.BLOCKS_PER_SECTION, workspace.blockCapacity());

        workspace.setBlockId(2, -63, 7, 42);
        workspace.setHeightCandidate(2, 7, 95);

        assertEquals(42, workspace.blockId(2, -63, 7));
        assertEquals(95, workspace.heightCandidate(2, 7));
        assertTrue(workspace.isDirtySection(0));
        assertTrue(workspace.isDirtyColumn(2, 7));
    }

    @Test
    void resetClearsStateButRetainsReasonableBuffers() {
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        workspace.begin(chunk(0, 0, -64, 384, 24, -4, 20), true);
        int[] blocks = workspace.blockIds();
        int[] heights = workspace.heightCandidates();

        workspace.setBlockId(1, -64, 1, 7);
        workspace.reset();

        assertFalse(workspace.imported());
        assertFalse(workspace.blockBufferEnabled());
        assertEquals(0, workspace.blockCapacity());
        assertSame(blocks, workspace.blockIds());
        assertSame(heights, workspace.heightCandidates());
        assertEquals(GAChunkWorkspace.UNKNOWN_HEIGHT, workspace.heightCandidate(1, 1));
        assertFalse(workspace.isDirtyColumn(1, 1));
    }

    @Test
    void releaseShrinksBuffersAboveMaxRetainedLimit() {
        GAChunkWorkspace workspace = new GAChunkWorkspace(1_024, GAChunkWorkspace.COLUMN_COUNT, 1);
        workspace.ensureBlockBufferCapacity(8_192);
        assertNotNull(workspace.blockIds());

        workspace.release();

        assertFalse(workspace.active());
        assertNull(workspace.blockIds());
        assertTrue(workspace.estimatedRetainedBytes() < 8_192L * Integer.BYTES);
    }

    @Test
    void blockAccessRequiresAllocatedBuffer() {
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        workspace.begin(chunk(0, 0, 0, 256, 16, 0, 16));

        assertThrows(IllegalStateException.class, () -> workspace.setBlockId(0, 0, 0, 1));
    }

    private static ChunkAccess chunk(int chunkX, int chunkZ, int minY, int height, int sections, int minSection, int maxSection) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(chunkX, chunkZ));
        when(chunk.getMinBuildHeight()).thenReturn(minY);
        when(chunk.getHeight()).thenReturn(height);
        when(chunk.getSectionsCount()).thenReturn(sections);
        when(chunk.getMinSection()).thenReturn(minSection);
        when(chunk.getMaxSection()).thenReturn(maxSection);
        return chunk;
    }
}
