package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * Runtime lifecycle wrapper for Phase 1 workspaces.
 *
 * <p>It keeps Minecraft/Moonrise chunk graph ownership intact: import on the
 * workspace lane, bind for GA-controlled generation, then deterministic repack
 * on the commit lane only if workspace sections became dirty.</p>
 */
public final class GAChunkWorkspaceRuntime {
    private GAChunkWorkspaceRuntime() {
    }

    public static Session acquireImported(ChunkAccess chunk) {
        Objects.requireNonNull(chunk, "chunk");

        GAChunkWorkspace workspace;
        try {
            workspace = GAChunkWorkspacePool.acquire(chunk, false);
        } catch (RuntimeException failure) {
            GAChunkWorkspaceMetrics.incrementImportFailures();
            logImportFailure(chunk, failure);
            return Session.empty(chunk);
        }

        if (workspace == null) {
            return Session.empty(chunk);
        }

        try {
            GAChunkWorkspace importedWorkspace = workspace;
            GAScheduler.invokeBlocking(GAScheduler.Lane.WORKSPACE,
                    () -> GAChunkBlockIo.importToWorkspace(chunk, importedWorkspace));
            return new Session(chunk, workspace);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            GAChunkWorkspaceMetrics.incrementImportFailures();
            logImportFailure(chunk, interrupted);
            GAChunkWorkspacePool.release(workspace);
            return Session.empty(chunk);
        } catch (ExecutionException | RuntimeException failure) {
            GAChunkWorkspaceMetrics.incrementImportFailures();
            logImportFailure(chunk, unwrap(failure));
            GAChunkWorkspacePool.release(workspace);
            return Session.empty(chunk);
        }
    }

    private static void finalizeWorkspace(ChunkAccess chunk, GAChunkWorkspace workspace) {
        if (workspace == null) {
            return;
        }

        long start = System.nanoTime();
        try {
            if (workspace.hasDirtySections()) {
                GAScheduler.invokeBlocking(GAScheduler.Lane.COMMIT,
                        () -> replayFinalRepackPlan(chunk, workspace));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            GAChunkWorkspaceMetrics.incrementFinalizeFailures();
            logFinalizeFailure(chunk, interrupted);
        } catch (ExecutionException | RuntimeException failure) {
            GAChunkWorkspaceMetrics.incrementFinalizeFailures();
            logFinalizeFailure(chunk, unwrap(failure));
        } finally {
            workspace.metrics().addFinalizeNanos(System.nanoTime() - start);
        }
    }

    private static void replayFinalRepackPlan(ChunkAccess chunk, GAChunkWorkspace workspace) {
        int[] dirtySections = workspace.dirtySectionIndices();
        for (int dirtySection : dirtySections) {
            GAChunkBlockIo.repackDirtySection(chunk, workspace, dirtySection);
        }
        workspace.clearCommittedBlockDirties();
    }

    private static GATerrainWorkspacePipeline.Result runTerrainPipeline(
            ChunkAccess chunk,
            GAChunkWorkspace workspace,
            GATerrainWorkspacePipeline.Plan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        if (workspace == null) {
            return null;
        }

        try (GAChunkWorkspaceContext.Scope ignored = GAChunkWorkspaceContext.bind(workspace)) {
            return GATerrainWorkspacePipeline.runBlocking(workspace, plan);
        } catch (RuntimeException failure) {
            GAChunkWorkspaceMetrics.incrementTerrainFailures();
            logTerrainFailure(chunk, failure);
            return null;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof ExecutionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static void logImportFailure(ChunkAccess chunk, Throwable failure) {
        GeneratorAccelerator.LOGGER.warn(
                "GA chunk workspace import failed for chunk {}; continuing without workspace.",
                chunkLabel(chunk),
                failure
        );
    }

    private static void logFinalizeFailure(ChunkAccess chunk, Throwable failure) {
        GeneratorAccelerator.LOGGER.warn(
                "GA chunk workspace finalize failed for chunk {}; vanilla chunk state remains authoritative.",
                chunkLabel(chunk),
                failure
        );
    }

    private static void logTerrainFailure(ChunkAccess chunk, Throwable failure) {
        GeneratorAccelerator.LOGGER.warn(
                "GA terrain workspace pipeline failed for chunk {}; continuing with vanilla terrain ownership.",
                chunkLabel(chunk),
                failure
        );
    }

    private static String chunkLabel(ChunkAccess chunk) {
        try {
            ChunkPos pos = chunk.getPos();
            return "[" + pos.x + ", " + pos.z + "]";
        } catch (RuntimeException failure) {
            return "[unknown]";
        }
    }

    public static final class Session implements AutoCloseable {
        private final ChunkAccess chunk;
        private final GAChunkWorkspace workspace;
        private boolean finalized;
        private boolean closed;

        private Session(ChunkAccess chunk, GAChunkWorkspace workspace) {
            this.chunk = chunk;
            this.workspace = workspace;
        }

        private static Session empty(ChunkAccess chunk) {
            return new Session(chunk, null);
        }

        public GAChunkWorkspace workspace() {
            return workspace;
        }

        public boolean active() {
            return workspace != null;
        }

        public GATerrainWorkspacePipeline.Result runTerrainPipeline(GATerrainWorkspacePipeline.Plan plan) {
            return GAChunkWorkspaceRuntime.runTerrainPipeline(chunk, workspace, plan);
        }

        public void finalizeWorkspace() {
            if (finalized) {
                return;
            }
            finalized = true;
            GAChunkWorkspaceRuntime.finalizeWorkspace(chunk, workspace);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                finalizeWorkspace();
            } finally {
                GAChunkWorkspacePool.release(workspace);
            }
        }
    }
}
