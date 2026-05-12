package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Mirrors owned decoration writes into the current chunk workspace.
 */
final class DecorationWorkspaceBridge {
    private static final boolean CURRENT_WORKSPACE_BRIDGE_ENABLED =
            Boolean.getBoolean("ga.decorationWorkspace.currentBridge");

    private DecorationWorkspaceBridge() {
    }

    static BlockState readCurrentWorkspaceBlock(BlockPos pos) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return null;
        }
        return readBlock(GAChunkWorkspaceContext.current(), pos);
    }

    static boolean hasCurrentWorkspace() {
        return CURRENT_WORKSPACE_BRIDGE_ENABLED && GAChunkWorkspaceContext.current() != null;
    }

    static boolean hasCurrentWorkspace(GAChunkWorkspace workspace) {
        return CURRENT_WORKSPACE_BRIDGE_ENABLED && workspace != null;
    }

    static BlockState readBlock(GAChunkWorkspace workspace, BlockPos pos) {
        LocalBlock local = localBlock(workspace, null, pos.getX(), pos.getY(), pos.getZ());
        if (local == null || !workspace.blockBufferEnabled()) {
            return null;
        }
        try {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_READ_HITS);
            return FastBlockStateCache.getBlockState(workspace.blockId(local.localX(), pos.getY(), local.localZ()));
        } catch (RuntimeException failure) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRROR_SKIPS);
            return null;
        }
    }

    static boolean mirrorCurrentWorkspaceWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return false;
        }
        return mirrorWrite(GAChunkWorkspaceContext.current(), chunk, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    static boolean mirrorCurrentWorkspaceWrite(ChunkAccess chunk, int x, int y, int z, BlockState state) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return false;
        }
        return mirrorWrite(GAChunkWorkspaceContext.current(), chunk, x, y, z, state);
    }

    static boolean mirrorWrite(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z, BlockState state) {
        if (workspace == null || state == null || !workspace.blockBufferEnabled()) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRROR_SKIPS);
            return false;
        }
        LocalBlock local = localBlock(workspace, chunk, x, y, z);
        if (local == null) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRROR_SKIPS);
            return false;
        }

        try {
            int blockId = Block.getId(state);
            workspace.setBlockIdIfChanged(local.localX(), y, local.localZ(), blockId);
            workspace.markDirtyHeightColumn(local.localX(), local.localZ());
            workspace.markDirtySurfaceColumn(local.localX(), local.localZ());
            workspace.markDirtyLightColumn(local.localX(), local.localZ());
            if (!state.isAir()) {
                int height = workspace.heightCandidate(local.localX(), local.localZ());
                if (height == GAChunkWorkspace.UNKNOWN_HEIGHT || y >= height) {
                    workspace.setHeightCandidate(local.localX(), local.localZ(), y);
                    if (workspace.surfaceBufferEnabled()) {
                        workspace.setSurfaceBlockId(local.localX(), local.localZ(), blockId);
                    }
                }
            }
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRRORS);
            return true;
        } catch (RuntimeException failure) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRROR_SKIPS);
            return false;
        }
    }

    private static LocalBlock localBlock(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z) {
        if (workspace == null) {
            return null;
        }
        if (chunk != null && (chunk.getPos().x != workspace.chunkX() || chunk.getPos().z != workspace.chunkZ())) {
            return null;
        }
        if (y < workspace.minBuildHeight() || y >= workspace.minBuildHeight() + workspace.buildHeight()) {
            return null;
        }
        int localX = x - workspace.minBlockX();
        int localZ = z - workspace.minBlockZ();
        if ((localX | localZ) < 0 || localX >= GAChunkWorkspace.CHUNK_WIDTH || localZ >= GAChunkWorkspace.CHUNK_WIDTH) {
            return null;
        }
        return new LocalBlock(localX, localZ);
    }

    private record LocalBlock(int localX, int localZ) {
    }
}
