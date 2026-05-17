package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecorationWorkspaceBridgeTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrapHelper.ensureBootstrapped();
    }

    @Test
    void mirrorsOwnedDecorationWriteIntoCurrentWorkspace() {
        ChunkAccess chunk = chunkAt(2, -3);
        GAChunkWorkspace workspace = workspace(chunk);
        workspace.ensureSurfaceBuffer();
        int x = chunk.getPos().getMinBlockX() + 5;
        int z = chunk.getPos().getMinBlockZ() + 7;

        boolean mirrored = DecorationWorkspaceBridge.mirrorWrite(
                workspace,
                chunk,
                x,
                12,
                z,
                Block.getId(Blocks.DIRT.defaultBlockState())
        );

        assertTrue(mirrored);
        assertEquals(Block.getId(Blocks.DIRT.defaultBlockState()), workspace.blockId(5, 12, 7));
        assertEquals(12, workspace.heightCandidate(5, 7));
        assertEquals(Block.getId(Blocks.DIRT.defaultBlockState()), workspace.surfaceBlockId(5, 7));
        assertTrue(workspace.isDirtyBlockColumn(5, 7));
        assertTrue(workspace.isDirtyHeightColumn(5, 7));
        assertTrue(workspace.isDirtySurfaceColumn(5, 7));
        assertTrue(workspace.isDirtyLightColumn(5, 7));
        assertTrue(workspace.isDirtySection(0));
    }

    @Test
    void readsOwnedDecorationBlockFromWorkspaceSnapshot() {
        ChunkAccess chunk = chunkAt(0, 0);
        GAChunkWorkspace workspace = workspace(chunk);
        workspace.setBlockId(1, 4, 2, Block.getId(Blocks.STONE.defaultBlockState()));

        assertEquals(
                Blocks.STONE.defaultBlockState(),
                DecorationWorkspaceBridge.readBlock(workspace, new BlockPos(1, 4, 2))
        );
    }

    @Test
    void skipsForeignChunkWrites() {
        ChunkAccess owner = chunkAt(0, 0);
        ChunkAccess foreign = chunkAt(1, 0);
        GAChunkWorkspace workspace = workspace(owner);

        assertFalse(DecorationWorkspaceBridge.mirrorWrite(
                workspace,
                foreign,
                foreign.getPos().getMinBlockX(),
                4,
                0,
                Block.getId(Blocks.STONE.defaultBlockState())
        ));
    }

    private static GAChunkWorkspace workspace(ChunkAccess chunk) {
        GAChunkWorkspace workspace = new GAChunkWorkspace();
        workspace.begin(chunk, true);
        workspace.importBlockIds((localX, y, localZ) -> Block.getId(Blocks.AIR.defaultBlockState()));
        return workspace;
    }

    private static ChunkAccess chunkAt(int chunkX, int chunkZ) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(chunkX, chunkZ));
        when(chunk.getMinBuildHeight()).thenReturn(0);
        when(chunk.getHeight()).thenReturn(16);
        when(chunk.getSectionsCount()).thenReturn(1);
        when(chunk.getMinSection()).thenReturn(0);
        when(chunk.getMaxSection()).thenReturn(1);
        return chunk;
    }
}
