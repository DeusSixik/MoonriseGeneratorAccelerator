package dev.sixik.generator_accelerator.common.worldgen.workspace;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GATerrainWorkspacePipelineTest {
    @Test
    void pipelineRunsTerrainPassesInContractOrderAndSetsReadyFlags() {
        GAChunkWorkspace workspace = workspace(0, 16, 1);
        List<String> order = new ArrayList<>();
        boolean[] seen = new boolean[5];

        GATerrainWorkspacePipeline.Result result = GATerrainWorkspacePipeline.run(workspace,
                GATerrainWorkspacePipeline.plan(
                                (localX, y, localZ) -> {
                                    once(order, seen, 1, "density");
                                    return y - 4.0D;
                                },
                                (localX, y, localZ) -> {
                                    once(order, seen, 2, "aquifer");
                                    return y < 3 ? 7 : 8;
                                },
                                (localX, y, localZ, blockId) -> {
                                    once(order, seen, 3, "surface");
                                    return blockId != GAChunkWorkspace.EMPTY_BLOCK_ID;
                                },
                                (localX, y, localZ, blockId) -> {
                                    once(order, seen, 4, "carver");
                                    return localX == 1 && y == 2 && localZ == 3;
                                },
                                GAChunkWorkspace.EMPTY_BLOCK_ID
                        )
                        .importBlocks((localX, y, localZ) -> {
                            once(order, seen, 0, "import");
                            return y <= 5 ? 9 : GAChunkWorkspace.EMPTY_BLOCK_ID;
                        })
        );
        order.add(workspace.terrainFinalized() ? "finalize" : "missing-finalize");

        assertEquals(List.of("import", "density", "aquifer", "surface", "carver", "finalize"), order);
        assertEquals(GAChunkWorkspace.BLOCKS_PER_SECTION, result.importedBlocks());
        assertEquals(GAChunkWorkspace.BLOCKS_PER_SECTION, result.densitySamples());
        assertEquals(GAChunkWorkspace.BLOCKS_PER_SECTION, result.aquiferDecisions());
        assertEquals(GAChunkWorkspace.COLUMN_COUNT, result.surfaceColumns());
        assertEquals(1L, result.carvedMaskBlocks());
        assertEquals(1L, result.carvedChangedBlocks());
        assertTrue(result.densityReady());
        assertTrue(result.aquiferReady());
        assertTrue(result.surfaceReady());
        assertTrue(result.carverReady());
        assertTrue(result.terrainFinalized());
        assertTrue(workspace.densityReady());
        assertTrue(workspace.aquiferReady());
        assertTrue(workspace.surfaceReady());
        assertTrue(workspace.carverReady());
        assertTrue(workspace.terrainFinalized());
    }

    @Test
    void pipelinePopulatesBiomeSurfaceHeightBuffersAndCarvesBlocks() {
        GAChunkWorkspace workspace = workspace(-16, 32, 2);
        workspace.importBlockIds((localX, y, localZ) -> y <= localX - 8 ? 4 : GAChunkWorkspace.EMPTY_BLOCK_ID);

        GATerrainWorkspacePipeline.Result result = GATerrainWorkspacePipeline.run(workspace,
                GATerrainWorkspacePipeline.plan(
                                (localX, y, localZ) -> localX + y + localZ,
                                (localX, y, localZ) -> y < 0 ? 2 : 3,
                                (localX, y, localZ, blockId) -> blockId == 4,
                                (localX, y, localZ, blockId) -> localX == 9 && y == 1 && localZ == 2,
                                GAChunkWorkspace.EMPTY_BLOCK_ID
                        )
                        .biomes((localX, localZ) -> 100 + localX + localZ)
        );

        assertEquals(0L, result.importedBlocks());
        assertEquals(GAChunkWorkspace.COLUMN_COUNT, result.biomeColumns());
        assertEquals(111, workspace.biomeId(6, 5));
        assertEquals(4, workspace.surfaceBlockId(9, 2));
        assertEquals(1, workspace.heightCandidate(9, 2));
        assertEquals(GAChunkWorkspace.EMPTY_BLOCK_ID, workspace.blockId(9, 1, 2));
        assertTrue(workspace.isDirtySurfaceColumn(9, 2));
        assertTrue(workspace.isDirtyHeightColumn(9, 2));
        assertTrue(workspace.isDirtyBlockColumn(9, 2));
        assertEquals(GAChunkWorkspace.COLUMN_COUNT, workspace.metrics().surfaceScannedColumns());
        assertEquals(GAChunkWorkspace.COLUMN_COUNT, workspace.metrics().heightUpdates());
        assertEquals(1L, workspace.metrics().finalizedWorkspaces());
    }

    @Test
    void releaseResetsCompletionFlagsAndLightweightBuffers() {
        GAChunkWorkspace workspace = workspace(0, 16, 1);
        GATerrainWorkspacePipeline.run(workspace,
                GATerrainWorkspacePipeline.plan(
                                (localX, y, localZ) -> 1.0D,
                                (localX, y, localZ) -> 2,
                                (localX, y, localZ, blockId) -> blockId != GAChunkWorkspace.EMPTY_BLOCK_ID,
                                (localX, y, localZ, blockId) -> false,
                                GAChunkWorkspace.EMPTY_BLOCK_ID
                        )
                        .importBlocks((localX, y, localZ) -> 3)
                        .biomes((localX, localZ) -> 4)
        );

        workspace.release();

        assertFalse(workspace.active());
        assertFalse(workspace.densityReady());
        assertFalse(workspace.aquiferReady());
        assertFalse(workspace.surfaceReady());
        assertFalse(workspace.carverReady());
        assertFalse(workspace.terrainFinalized());
        assertFalse(workspace.biomeBufferEnabled());
        assertFalse(workspace.surfaceBufferEnabled());
        assertEquals(GAChunkWorkspace.EMPTY_BLOCK_ID, workspace.surfaceBlockIds()[0]);
        assertEquals(0, workspace.biomeIds()[0]);
    }

    private static void once(List<String> order, boolean[] seen, int index, String name) {
        if (!seen[index]) {
            seen[index] = true;
            order.add(name);
        }
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
