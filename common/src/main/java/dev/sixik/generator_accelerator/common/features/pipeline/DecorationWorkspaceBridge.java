package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Mirrors owned decoration writes into the current chunk workspace.
 */
public final class DecorationWorkspaceBridge {
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean CURRENT_WORKSPACE_BRIDGE_ENABLED =
            booleanProperty("ga.decorationWorkspace.currentBridge", CONFIG.enableDecorationWorkspaceBridge);

    private DecorationWorkspaceBridge() {
    }

    public static boolean enabled() {
        return CURRENT_WORKSPACE_BRIDGE_ENABLED;
    }

    public static BlockState readCurrentWorkspaceBlock(BlockPos pos) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return null;
        }
        return readBlock(GAChunkWorkspaceContext.current(), pos);
    }

    public static boolean hasCurrentWorkspace() {
        return CURRENT_WORKSPACE_BRIDGE_ENABLED && GAChunkWorkspaceContext.current() != null;
    }

    public static boolean hasCurrentWorkspace(GAChunkWorkspace workspace) {
        return CURRENT_WORKSPACE_BRIDGE_ENABLED && workspace != null;
    }

    public static BlockState readBlock(GAChunkWorkspace workspace, BlockPos pos) {
        BlockState state = GAWorkspaceWriteBridge.read(workspace, pos);
        if (state == null) {
            return null;
        }
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_READ_HITS);
        return state;
    }

    public static boolean mirrorCurrentWorkspaceWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return false;
        }
        return mirrorWrite(GAChunkWorkspaceContext.current(), chunk, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public static boolean mirrorCurrentWorkspaceWrite(ChunkAccess chunk, int x, int y, int z, BlockState state) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return false;
        }
        return mirrorWrite(GAChunkWorkspaceContext.current(), chunk, x, y, z, state);
    }

    public static boolean mirrorWrite(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z, BlockState state) {
        if (GAWorkspaceWriteBridge.mirror(workspace, chunk, x, y, z, state)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRRORS);
            return true;
        }
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRROR_SKIPS);
        return false;
    }

    public static boolean writeCurrentWorkspaceOnly(ChunkAccess chunk, BlockPos pos, BlockState state) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return false;
        }
        return writeWorkspaceOnly(GAChunkWorkspaceContext.current(), chunk, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public static boolean writeCurrentWorkspaceOnly(ChunkAccess chunk, int x, int y, int z, BlockState state) {
        if (!CURRENT_WORKSPACE_BRIDGE_ENABLED) {
            return false;
        }
        return writeWorkspaceOnly(GAChunkWorkspaceContext.current(), chunk, x, y, z, state);
    }

    public static boolean writeWorkspaceOnly(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z, BlockState state) {
        if (GAWorkspaceWriteBridge.writeWorkspaceOnly(workspace, chunk, x, y, z, state)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRRORS);
            return true;
        }
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_BLOCK_MIRROR_SKIPS);
        return false;
    }

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
