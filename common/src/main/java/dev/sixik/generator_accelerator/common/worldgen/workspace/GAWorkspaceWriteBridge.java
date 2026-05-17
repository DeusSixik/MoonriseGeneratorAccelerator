package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACrossChunkMailboxRuntime;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public bridge for optimized code paths that bypass WorldGenRegion#setBlock.
 */
public final class GAWorkspaceWriteBridge {
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean WORKSPACE_ONLY_WRITES_ENABLED = booleanProperty(
            "ga.workspaceOnlyBlockWrites.enabled",
            CONFIG.enableWorkspaceOnlyBlockWrites
    );
    private static final boolean KNOWN_DECORATION_JOURNAL_WRITES_ENABLED = booleanProperty(
            "ga.knownDecorationJournalWrites.enabled",
            CONFIG.enableKnownDecorationJournalWrites
    );
    private static final boolean WORKSPACE_ONLY_CIRCUIT_BREAKER_ENABLED = booleanProperty(
            "ga.workspaceOnlyBlockWrites.circuitBreaker.enabled",
            CONFIG.enableWorkspaceOnlyCircuitBreaker
    );
    private static final AtomicBoolean WORKSPACE_ONLY_RUNTIME_DISABLED = new AtomicBoolean();
    private static final AtomicReference<String> WORKSPACE_ONLY_DISABLE_REASON = new AtomicReference<>();

    private GAWorkspaceWriteBridge() {
    }

    public static boolean workspaceOnlyWritesEnabled() {
        return WORKSPACE_ONLY_WRITES_ENABLED && !WORKSPACE_ONLY_RUNTIME_DISABLED.get();
    }

    public static boolean knownDecorationJournalWritesEnabled() {
        return KNOWN_DECORATION_JOURNAL_WRITES_ENABLED;
    }

    public static boolean workspaceOnlyWritesRuntimeDisabled() {
        return WORKSPACE_ONLY_RUNTIME_DISABLED.get();
    }

    public static String workspaceOnlyDisableReason() {
        return WORKSPACE_ONLY_DISABLE_REASON.get();
    }

    public static void disableWorkspaceOnlyWritesForSession(String reason, Throwable failure) {
        if (!WORKSPACE_ONLY_CIRCUIT_BREAKER_ENABLED) {
            return;
        }
        String safeReason = reason == null || reason.isBlank() ? "workspace-only safety circuit breaker" : reason;
        if (WORKSPACE_ONLY_RUNTIME_DISABLED.compareAndSet(false, true)) {
            WORKSPACE_ONLY_DISABLE_REASON.set(safeReason);
            GeneratorAccelerator.LOGGER.warn(
                    "GA workspace-only block writes disabled for this session: {}",
                    safeReason,
                    failure
            );
        }
    }

    public static void resetWorkspaceOnlyCircuitBreakerForTests() {
        WORKSPACE_ONLY_RUNTIME_DISABLED.set(false);
        WORKSPACE_ONLY_DISABLE_REASON.set(null);
    }

    public static BlockState readCurrent(BlockPos pos) {
        return read(GAChunkWorkspaceContext.current(), pos);
    }

    public static int readBlockIdCurrent(int x, int y, int z, int fallbackBlockId) {
        Integer blockId = readBlockId(GAChunkWorkspaceContext.current(), x, y, z);
        return blockId == null ? fallbackBlockId : blockId;
    }

    public static Integer readBlockIdCurrent(int x, int y, int z) {
        return readBlockId(GAChunkWorkspaceContext.current(), x, y, z);
    }

    public static BlockState read(GAChunkWorkspace workspace, BlockPos pos) {
        GADecorationWriteJournal journal = GADecorationJournalContext.current();
        if (journal != null) {
            BlockState state = journal.stateAt(pos);
            if (state != null) {
                return state;
            }
        }
        if (workspace == null || pos == null || !workspace.blockBufferEnabled()) {
            return null;
        }
        Integer blockId = readBlockId(workspace, pos.getX(), pos.getY(), pos.getZ());
        return blockId == null ? null : FastBlockStateCache.getBlockState(blockId);
    }

    public static Integer readBlockId(GAChunkWorkspace workspace, int x, int y, int z) {
        GADecorationWriteJournal journal = GADecorationJournalContext.current();
        if (journal != null) {
            Integer blockId = journal.blockIdAt(x, y, z);
            if (blockId != null) {
                return blockId;
            }
        }
        if (workspace == null || !workspace.blockBufferEnabled()) {
            return null;
        }
        LocalBlock local = localBlock(workspace, null, x, y, z);
        if (local == null) {
            return null;
        }
        try {
            return workspace.blockId(local.localX(), y, local.localZ());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static boolean mirrorCurrent(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return mirrorCurrent(chunk, pos, GA$BlockStateExtension.get(state).bts$getFastId());
    }

    public static boolean mirrorCurrent(ChunkAccess chunk, BlockPos pos, int state) {
        if (pos == null) {
            return false;
        }
        return mirrorCurrent(chunk, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public static boolean mirrorCurrent(ChunkAccess chunk, int x, int y, int z, int state) {
        return mirror(GAChunkWorkspaceContext.current(), chunk, x, y, z, state);
    }

    public static boolean mirror(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z, int state) {
        return write(workspace, chunk, x, y, z, state, false);
    }

    public static boolean writeCurrentWorkspaceOnly(ChunkAccess chunk, BlockPos pos, int state) {
        if (pos == null) {
            return false;
        }
        return writeCurrentWorkspaceOnly(chunk, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public static boolean writeCurrentWorkspaceOnly(ChunkAccess chunk, int x, int y, int z, int state) {
        return writeWorkspaceOnly(GAChunkWorkspaceContext.current(), chunk, x, y, z, state);
    }

    public static boolean writeWorkspaceOnly(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z, int state) {
        return write(workspace, chunk, x, y, z, state, true, false);
    }

    public static boolean writeCurrentKnownDecorationWorkspaceOnly(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return writeCurrentKnownDecorationWorkspaceOnly(chunk, pos, GA$BlockStateExtension.get(state).bts$getFastId());
    }

    public static boolean writeCurrentKnownDecorationWorkspaceOnly(ChunkAccess chunk, BlockPos pos, int state) {
        if (pos == null) {
            return false;
        }
        return writeCurrentKnownDecorationWorkspaceOnly(chunk, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public static boolean writeCurrentKnownDecorationWorkspaceOnly(ChunkAccess chunk, int x, int y, int z, int state) {
        return writeKnownDecorationWorkspaceOnly(GAChunkWorkspaceContext.current(), chunk, x, y, z, state);
    }

    public static boolean writeKnownDecorationWorkspaceOnly(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z, int state) {
        if (!KNOWN_DECORATION_JOURNAL_WRITES_ENABLED) {
            return false;
        }
        return write(workspace, chunk, x, y, z, state, true, true);
    }

    private static boolean write(GAChunkWorkspace workspace, ChunkAccess chunk, int x, int y, int z, int state, boolean workspaceOnly) {
        return write(workspace, chunk, x, y, z, state, workspaceOnly, false);
    }

    private static boolean write(
            GAChunkWorkspace workspace,
            ChunkAccess chunk,
            int x,
            int y,
            int z,
            int blockId,
            boolean workspaceOnly,
            boolean trustedKnownDecoration
    ) {
        if (workspaceOnly && WORKSPACE_ONLY_RUNTIME_DISABLED.get()) {
            return false;
        }
        if (workspaceOnly && !trustedKnownDecoration && !WORKSPACE_ONLY_WRITES_ENABLED) {
            return false;
        }
        if (workspace == null || blockId == -1 || !workspace.blockBufferEnabled()) {
            return false;
        }
        if (trustedKnownDecoration) {
            GADecorationWriteJournal journal = GADecorationJournalContext.current();
            if (journal != null) {
                if (y < workspace.minBuildHeight() || y >= workspace.minBuildHeight() + workspace.buildHeight()) {
                    return false;
                }
                return journal.add(x, y, z, blockId);
            }
        }
        if (workspaceOnly && ((x >> 4) != workspace.chunkX() || (z >> 4) != workspace.chunkZ())) {
            return GACrossChunkMailboxRuntime.enqueueBlockWrite(
                    workspace.chunkX(),
                    workspace.chunkZ(),
                    x,
                    y,
                    z,
                    blockId,
                    2
            );
        }
        LocalBlock local = localBlock(workspace, chunk, x, y, z);
        if (local == null) {
            return false;
        }
        try {
            if (workspaceOnly) {
                workspace.setBlockIdWorkspaceOnlyIfChanged(local.localX(), y, local.localZ(), blockId);
            } else {
                workspace.setBlockIdMirroredIfChanged(local.localX(), y, local.localZ(), blockId);
            }
            workspace.markDirtyHeightColumn(local.localX(), local.localZ());
            workspace.markDirtySurfaceColumn(local.localX(), local.localZ());
            workspace.markDirtyLightColumn(local.localX(), local.localZ());
            if (!FastBlockStateCache.isAir(blockId)) {
                int height = workspace.heightCandidate(local.localX(), local.localZ());
                if (height == GAChunkWorkspace.UNKNOWN_HEIGHT || y >= height) {
                    workspace.setHeightCandidate(local.localX(), local.localZ(), y);
                    if (workspace.surfaceBufferEnabled()) {
                        workspace.setSurfaceBlockId(local.localX(), local.localZ(), blockId);
                    }
                }
            }
            return true;
        } catch (RuntimeException ignored) {
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

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
