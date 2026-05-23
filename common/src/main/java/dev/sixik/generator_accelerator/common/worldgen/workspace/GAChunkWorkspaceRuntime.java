package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockPosition;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitBatch;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionPolicy;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCommand;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitEngine;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitOrderKey;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACrossChunkMailboxRuntime;
import dev.sixik.generator_accelerator.common.worldgen.commit.GAFinalRepackValue;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import dev.sixik.generator_accelerator.diagnostics.GAWallTimeTelemetry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Runtime lifecycle wrapper for Phase 1 workspaces.
 *
 * <p>It keeps Minecraft/Moonrise chunk graph ownership intact: import on the
 * workspace lane, bind for GA-controlled generation, then deterministic repack
 * on the commit lane only if workspace sections became dirty.</p>
 */
public final class GAChunkWorkspaceRuntime {
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean RUNTIME_ENABLED = booleanProperty(
            "ga.chunkWorkspace.runtime.enabled",
            CONFIG.enableChunkWorkspaceRuntime
    );
    private static final boolean FINAL_REPACK_ENABLED = booleanProperty(
            "ga.chunkWorkspace.finalRepack.enabled",
            CONFIG.enableWorkspaceFinalRepack
    );
    private static final boolean TERRAIN_AIR_IMPORT_ENABLED = booleanProperty(
            "ga.chunkWorkspace.terrain.airImport.enabled",
            CONFIG.enableWorkspaceTerrainAirImport
    );
    private static final boolean LOCAL_TERRAIN_FINAL_REPACK_ENABLED = booleanProperty(
            "ga.chunkWorkspace.terrain.localFinalRepack.enabled",
            CONFIG.enableWorkspaceLocalTerrainFinalRepack
    );
    private static final boolean BIND_TERRAIN_WITHOUT_WORKSPACE_ONLY = booleanProperty(
            "ga.chunkWorkspace.terrain.bindWithoutWorkspaceOnly.enabled",
            false
    );

    private GAChunkWorkspaceRuntime() {
    }

    public static boolean runtimeEnabled() {
        return RUNTIME_ENABLED;
    }

    public static boolean finalRepackEnabled() {
        return FINAL_REPACK_ENABLED;
    }

    public static CompletableFuture<ChunkAccess> drainCrossChunkMailboxAfter(CompletableFuture<ChunkAccess> future) {
        CompletableFuture<ChunkAccess> checked = requireFuture(future);
        if (!shouldAttachCrossChunkMailboxDrain()) {
            return checked;
        }
        return checked.thenApply(chunk -> {
            long timer = GAWallTimeTelemetry.start(GAWallTimeTelemetry.Stage.CHUNK_STATUS_HANDOFF);
            try {
                drainCrossChunkMailboxIfQueued(chunk);
            } finally {
                GAWallTimeTelemetry.end(GAWallTimeTelemetry.Stage.CHUNK_STATUS_HANDOFF, timer);
            }
            return chunk;
        });
    }

    public static void drainCrossChunkMailboxIfQueued(ChunkAccess chunk) {
        if (!GACrossChunkMailboxRuntime.enabled() || !GACrossChunkMailboxRuntime.hasQueuedBlockWrites(chunk)) {
            return;
        }
        try {
            GAScheduler.invokeBlocking(GAScheduler.Lane.COMMIT, () -> drainCrossChunkMailbox(chunk));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining workspace cross-chunk mailbox", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("workspace cross-chunk mailbox drain failed", unwrap(failure));
        }
    }

    private static boolean shouldAttachCrossChunkMailboxDrain() {
        return GACrossChunkMailboxRuntime.enabled()
                && (GACrossChunkMailboxRuntime.queuedCommands() > 0
                || GAWorkspaceWriteBridge.workspaceOnlyWritesEnabled()
                || GAWorkspaceWriteBridge.knownDecorationJournalWritesEnabled());
    }

    public static <T> T withImportedWorkspace(ChunkAccess chunk, Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        if (!RUNTIME_ENABLED) {
            return task.get();
        }

        Session session = acquireImported(chunk);
        boolean success = false;
        try {
            if (!session.active()) {
                T result = task.get();
                success = true;
                return result;
            }
            GAChunkWorkspaceMetrics.incrementContextBoundSessions();
            try (GAChunkWorkspaceContext.Scope ignored = GAChunkWorkspaceContext.bind(session.workspace())) {
                T result = task.get();
                success = true;
                return result;
            }
        } finally {
            if (!success) {
                session.discard();
            }
            session.close();
        }
    }

    public static <T> CompletableFuture<T> withImportedWorkspaceFuture(
            ChunkAccess chunk,
            Supplier<CompletableFuture<T>> task
    ) {
        Objects.requireNonNull(task, "task");
        if (!RUNTIME_ENABLED) {
            return requireFuture(task.get());
        }

        Session session = acquireImported(chunk);
        if (!session.active()) {
            return requireFuture(task.get());
        }

        GAChunkWorkspaceMetrics.incrementContextBoundSessions();
        CompletableFuture<T> future;
        try (GAChunkWorkspaceContext.Scope ignored = GAChunkWorkspaceContext.bind(session.workspace())) {
            future = requireFuture(task.get());
        } catch (RuntimeException | Error failure) {
            session.discard();
            session.close();
            throw failure;
        }

        return future.whenComplete((ignored, failure) -> {
            if (failure != null) {
                session.discard();
            }
            session.close();
        });
    }

    public static <T> CompletableFuture<T> withTerrainWorkspaceFuture(
            ChunkAccess chunk,
            Supplier<CompletableFuture<T>> task
    ) {
        Objects.requireNonNull(task, "task");
        if (!RUNTIME_ENABLED) {
            return requireFuture(task.get());
        }
        if (!shouldBindTerrainWorkspace()) {
            return requireFuture(task.get());
        }

        Session session = acquireTerrainWorkspace(chunk);
        if (!session.active()) {
            return requireFuture(task.get());
        }

        GAChunkWorkspaceMetrics.incrementContextBoundSessions();
        CompletableFuture<T> future;
        try (GAChunkWorkspaceContext.Scope ignored = GAChunkWorkspaceContext.bind(session.workspace())) {
            future = requireFuture(task.get());
        } catch (RuntimeException | Error failure) {
            session.discard();
            session.close();
            throw failure;
        }

        return future.whenComplete((ignored, failure) -> {
            if (failure != null) {
                session.discard();
            }
            session.close();
        });
    }

    public static Session acquireImported(ChunkAccess chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (!RUNTIME_ENABLED) {
            return Session.empty(chunk);
        }

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

    private static Session acquireTerrainWorkspace(ChunkAccess chunk) {
        if (TERRAIN_AIR_IMPORT_ENABLED) {
            try {
                if (GAChunkBlockIo.canInitializeAirWorkspace(chunk)) {
                    return acquireAirInitialized(chunk);
                }
            } catch (RuntimeException ignored) {
                // Fall back to full import; correctness beats the terrain fast path.
            }
        }
        return acquireImported(chunk);
    }

    private static Session acquireAirInitialized(ChunkAccess chunk) {
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
            GAChunkBlockIo.initializeAirWorkspace(chunk, workspace);
            return new Session(chunk, workspace);
        } catch (RuntimeException failure) {
            GAChunkWorkspaceMetrics.incrementImportFailures();
            logImportFailure(chunk, failure);
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
            GAChunkWorkspaceMetrics.addMirroredBlockWrites(workspace.mirroredWrites());
            GAChunkWorkspaceMetrics.addWorkspaceOnlyBlockWrites(workspace.workspaceOnlyWrites());
            if (!FINAL_REPACK_ENABLED || !workspace.hasWorkspaceOnlyWrites()) {
                GAChunkWorkspaceMetrics.incrementFinalRepackSkips();
                drainCrossChunkMailboxIfQueued(chunk);
                return;
            }
            if (canFinalizeTerrainLocally(workspace)) {
                replayLocalTerrainFinalRepack(chunk, workspace);
                drainCrossChunkMailboxIfQueued(chunk);
                return;
            }
            GAScheduler.invokeBlocking(GAScheduler.Lane.COMMIT,
                    () -> {
                        if (workspace.hasDirtySections()) {
                            replayFinalRepackPlan(chunk, workspace);
                        }
                        drainCrossChunkMailbox(chunk);
                    });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            handleFinalizeFailure(chunk, workspace, interrupted);
        } catch (ExecutionException | RuntimeException failure) {
            handleFinalizeFailure(chunk, workspace, unwrap(failure));
        } finally {
            workspace.metrics().addFinalizeNanos(System.nanoTime() - start);
        }
    }

    private static boolean canFinalizeTerrainLocally(GAChunkWorkspace workspace) {
        return LOCAL_TERRAIN_FINAL_REPACK_ENABLED
                && workspace != null
                && workspace.terrainFinalized()
                && workspace.hasOnlyTerrainWorkspaceOnlyWrites();
    }

    private static boolean shouldBindTerrainWorkspace() {
        return GAWorkspaceWriteBridge.workspaceOnlyWritesEnabled() || BIND_TERRAIN_WITHOUT_WORKSPACE_ONLY;
    }

    private static void replayLocalTerrainFinalRepack(ChunkAccess chunk, GAChunkWorkspace workspace) {
        int[] dirtySections = workspace.dirtySectionIndices();
        if (dirtySections.length > 0) {
            GAChunkBlockIo.repackLocalTerrainDirtySections(chunk, workspace, dirtySections);
            GAChunkWorkspaceMetrics.addFinalRepackLocalTerrainSections(dirtySections.length);
        }
    }

    private static void handleFinalizeFailure(ChunkAccess chunk, GAChunkWorkspace workspace, Throwable failure) {
        if (workspace != null && workspace.hasWorkspaceOnlyWrites() && tryEmergencyRepack(chunk, workspace, failure)) {
            return;
        }
        GAChunkWorkspaceMetrics.incrementFinalizeFailures();
        if (workspace != null && workspace.hasWorkspaceOnlyWrites()) {
            GAWorkspaceWriteBridge.disableWorkspaceOnlyWritesForSession(
                    "workspace final repack failed and emergency repair did not complete",
                    failure
            );
        }
        logFinalizeFailure(chunk, failure);
    }

    private static boolean tryEmergencyRepack(ChunkAccess chunk, GAChunkWorkspace workspace, Throwable cause) {
        try {
            long written = GAChunkBlockIo.emergencyRepackDirtySections(chunk, workspace);
            GeneratorAccelerator.LOGGER.warn(
                    "GA chunk workspace final repack failed for {}; emergency replay wrote {} blocks and kept the chunk publishable.",
                    chunkLabel(chunk),
                    written,
                    cause
            );
            return true;
        } catch (RuntimeException emergencyFailure) {
            GAChunkWorkspaceMetrics.incrementEmergencyRepackFailures();
            cause.addSuppressed(emergencyFailure);
            return false;
        }
    }

    private static void drainCrossChunkMailbox(ChunkAccess chunk) {
        GACommitEngine.GACommitExecution<?> execution = GACrossChunkMailboxRuntime.drainBlockWrites(chunk);
        if (execution != null && !execution.failures().isEmpty()) {
            throw new IllegalStateException("workspace cross-chunk mailbox commit failed for "
                    + execution.failures().size() + " block writes");
        }
    }

    private static void replayFinalRepackPlan(ChunkAccess chunk, GAChunkWorkspace workspace) {
        int[] dirtySections = workspace.dirtySectionIndices();
        if (dirtySections.length == 0) {
            return;
        }

        List<GACommitCommand<GAFinalRepackValue>> commands = new ArrayList<>(dirtySections.length);
        for (int localIndex = 0; localIndex < dirtySections.length; localIndex++) {
            int dirtySection = dirtySections[localIndex];
            int sectionY = workspace.minSectionY() + dirtySection;
            commands.add(new GACommitCommand<>(
                    new GABlockPosition(workspace.minBlockX(), sectionY << 4, workspace.minBlockZ()),
                    GACommitOrderKey.chunkLocal(
                            0,
                            sectionY,
                            workspace.chunkX(),
                            workspace.chunkZ(),
                            localIndex,
                            localIndex
                    ),
                    new GAFinalRepackValue(sectionY, 0L, 0L)
            ));
        }

        GACommitEngine.GACommitExecution<GAFinalRepackValue> execution = GACommitEngine.execute(
                GACommitBatch.of(commands),
                GACommitCollisionPolicy.FIRST_WRITE_WINS,
                command -> {
                    int sectionIndex = command.value().sectionY() - workspace.minSectionY();
                    GAChunkBlockIo.repackDirtySection(chunk, workspace, sectionIndex);
                }
        );
        if (!execution.failures().isEmpty()) {
            throw new IllegalStateException("workspace final repack commit failed for "
                    + execution.failures().size() + " dirty sections");
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

    private static <T> CompletableFuture<T> requireFuture(CompletableFuture<T> future) {
        if (future == null) {
            throw new NullPointerException("worldgen task returned null future");
        }
        return future;
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

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
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

        public void discard() {
            finalized = true;
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
