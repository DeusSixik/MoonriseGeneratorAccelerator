package dev.sixik.generator_accelerator.common.worldgen.workspace;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GATerrainWorkspacePassesTest {
    @Test
    void importedBlockIdsScanIntoPreliminarySurfaceHeights() {
        GAChunkWorkspace workspace = workspace(0, 32, 2);
        workspace.importBlockIds((localX, y, localZ) -> y <= 7 + localX ? 1 : 0);

        long scanned = GATerrainWorkspacePasses.scanPreliminarySurfaceHeights(workspace, (localX, y, localZ, blockId) -> blockId != 0);

        assertEquals(GAChunkWorkspace.COLUMN_COUNT, scanned);
        assertEquals(7, workspace.heightCandidate(0, 0));
        assertEquals(10, workspace.heightCandidate(3, 12));
        assertTrue(workspace.isDirtyHeightColumn(3, 12));
        assertFalse(workspace.hasDirtySections());
        assertTrue(workspace.metrics().computeNanos() > 0L);
        assertEquals(1L, workspace.metrics().terrainPasses());
    }

    @Test
    void densityAndAquiferBuffersFillFromGenericSamplers() {
        GAChunkWorkspace workspace = workspace(-16, 32, 2);

        long densities = GATerrainWorkspacePasses.fillDensity(workspace,
                (localX, y, localZ) -> localX * 0.5D + y - localZ);
        long aquifers = GATerrainWorkspacePasses.fillAquifer(workspace,
                (localX, y, localZ) -> y < 0 ? 2 : 3);

        assertEquals(2L * GAChunkWorkspace.BLOCKS_PER_SECTION, densities);
        assertEquals(2L * GAChunkWorkspace.BLOCKS_PER_SECTION, aquifers);
        assertTrue(workspace.densityBufferEnabled());
        assertTrue(workspace.aquiferBufferEnabled());
        assertEquals(2.5D - 4D - 7D, workspace.density(5, -4, 7));
        assertEquals(2, workspace.aquiferBlockId(5, -4, 7));
        assertEquals(3, workspace.aquiferBlockId(5, 8, 7));
        assertEquals(2L, workspace.metrics().terrainPasses());
    }

    @Test
    void carverMaskAppliesAirAndMarksDirtySectionAndColumn() {
        GAChunkWorkspace workspace = workspace(0, 32, 2);
        workspace.importBlockIds((localX, y, localZ) -> 9);

        long masked = GATerrainWorkspacePasses.fillCarverMask(workspace,
                (localX, y, localZ, blockId) -> localX == 4 && localZ == 5 && y == 6);
        long changed = GATerrainWorkspacePasses.applyCarverMask(workspace, GAChunkWorkspace.EMPTY_BLOCK_ID);

        assertEquals(1L, masked);
        assertEquals(1L, changed);
        assertEquals(GAChunkWorkspace.EMPTY_BLOCK_ID, workspace.blockId(4, 6, 5));
        assertTrue(workspace.isDirtySection(0));
        assertFalse(workspace.isDirtySection(1));
        assertTrue(workspace.isDirtyBlockColumn(4, 5));
        assertEquals(1L, workspace.metrics().carvedBlocks());
    }

    @Test
    void repackAfterCarverStillWritesOneDirtySection() {
        GAChunkWorkspace workspace = workspace(0, 32, 2);
        workspace.importBlockIds((localX, y, localZ) -> 9);
        GATerrainWorkspacePasses.fillCarverMask(workspace,
                (localX, y, localZ, blockId) -> localX == 4 && localZ == 5 && y == 6);
        GATerrainWorkspacePasses.applyCarverMask(workspace, GAChunkWorkspace.EMPTY_BLOCK_ID);

        long repacked = workspace.repackDirtyBlockIds((localX, y, localZ, blockId) -> {
            if (y >= 16) {
                throw new AssertionError("clean section should not repack");
            }
        });

        assertEquals(GAChunkWorkspace.BLOCKS_PER_SECTION, repacked);
        assertFalse(workspace.hasDirtySections());
        assertTrue(workspace.metrics().repackNanos() > 0L);
    }

    private static GAChunkWorkspace workspace(int minY, int height, int sections) {
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        workspace.begin(chunk(minY, height, sections));
        return workspace;
    }

    private static ChunkAccess chunk(int minY, int height, int sections) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        when(chunk.getMinBuildHeight()).thenReturn(minY);
        when(chunk.getHeight()).thenReturn(height);
        when(chunk.getSectionsCount()).thenReturn(sections);
        when(chunk.getMinSection()).thenReturn(Math.floorDiv(minY, GAChunkWorkspace.CHUNK_WIDTH));
        when(chunk.getMaxSection()).thenReturn(Math.floorDiv(minY, GAChunkWorkspace.CHUNK_WIDTH) + sections);
        return chunk;
    }
}
