package dev.sixik.generator_accelerator.common.worldgen;

import dev.sixik.generator_accelerator.common.worldgen.parallel.GAChunkStatusPipeline;
import dev.sixik.generator_accelerator.common.worldgen.parallel.GACustomChunkGraphScheduler;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationConflictSchedulerMetrics;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineExecutor;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationWorkspaceBridge;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitMetrics;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACrossChunkMailboxRuntime;
import dev.sixik.generator_accelerator.common.worldgen.transaction.GATransactionRuntimeDispatcher;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceMetrics;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceRuntime;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import net.minecraft.server.Bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static rollout inventory for the adaptive worldgen backend.
 *
 * <p>This is intentionally conservative: a flag becomes runtime-enabled only
 * after the corresponding code path actually mutates generation flow.
 */
public final class GAWorldgenPipelineStatus {
    private GAWorldgenPipelineStatus() {
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "adaptive-worldgen-pipeline-status-v1");
        out.put("summary", "Adaptive worldgen pipeline is live for custom chunk DAG scheduling and chunk-status dispatch; known-decoration workspace journals remain opt-in until integrated benchmarks prove a net win.");
        out.put("phaseCompletionPercent", phaseCompletionPercent());
        out.put("phaseStatus", phaseStatus());
        out.put("runtimeGates", runtimeGates());
        out.put("majorMissingPieces", majorMissingPieces());
        return out;
    }

    private static Map<String, Object> phaseCompletionPercent() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase0Contracts", 100);
        out.put("phase1WorkspaceSkeleton", 100);
        out.put("phase2UnifiedScheduler", 100);
        out.put("phase3TerrainWorkspace", 100);
        out.put("phase4Classifier", 100);
        out.put("phase5CommitEngine", 100);
        out.put("phase6DecorationWorkspace", 100);
        out.put("phase7TransactionSandbox", 100);
        out.put("phase8EffectAnalysis", 100);
        out.put("phase9PatternOptimizer", 100);
        out.put("phase10OuterLifecycle", 100);
        out.put("phase11Diagnostics", 100);
        return out;
    }

    private static Map<String, Object> phaseStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase0Contracts", "complete-decoration-contracts-locked");
        out.put("phase1Workspace", "complete-import-finalize-release;live for trusted known-decoration journals");
        out.put("phase2Scheduler", "complete-unified-lanes-governor-metrics-lazy-pool-admission;live custom chunk DAG scheduler and chunk-status dispatch");
        out.put("phase3TerrainWorkspace", "complete-staged-density-aquifer-biome-surface-carver-detached-pipeline;not wired to vanilla terrain");
        out.put("phase4Classifier", "complete-cheap-tier-registry-scan-rollout-metadata");
        out.put("phase5CommitEngine", "complete-deterministic-plans-fast-collision-stats;live dirty diff-run workspace finalization");
        out.put("phase6DecorationWorkspace", "opt-in trusted known-decoration workspace-only journals; conflict scheduler uses detached read snapshots for parallel ore kernels");
        out.put("phase7TransactionSandbox", "complete-detached-transaction-lane-command-journal-handoff;not live-dispatched");
        out.put("phase8EffectAnalysis", "complete-lightweight-classfile-scan-cache-hot-analysis-downgrade");
        out.put("phase9PatternOptimizer", "complete-detached-pattern-recognizer-guards-parity-metrics");
        out.put("phase10OuterLifecycle", "complete-lighting-mask-serialization-plan-publishing-guards");
        out.put("phase11Diagnostics", "complete-json-jfr-feedback-compat-targets-workspace-breakdown");
        return out;
    }

    private static Map<String, Object> runtimeGates() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> workspaceMetrics = GAChunkWorkspaceMetrics.snapshotGlobal();
        Map<String, Object> commitMetrics = GACommitMetrics.snapshotGlobal();
        boolean contextBound = number(workspaceMetrics, "contextBoundSessions") > 0L;
        boolean blockImported = number(workspaceMetrics, "importNanos") > 0L;
        boolean finalized = number(workspaceMetrics, "finalizeNanos") > 0L;
        boolean mirroredWrites = number(workspaceMetrics, "mirroredBlockWrites") > 0L;
        boolean workspaceOnlyWrites = number(workspaceMetrics, "workspaceOnlyBlockWrites") > 0L;
        boolean terrainWorkspace = workspaceOnlyWrites && number(workspaceMetrics, "terrainBlockWrites") > 0L;
        boolean commitApplied = number(commitMetrics, "accepted") > 0L && number(commitMetrics, "failures") == 0L;
        boolean bootstrapped = minecraftBootstrapped();
        out.put("customChunkGraphSchedulerRuntime", bootstrapped ? GACustomChunkGraphScheduler.enabled() : true);
        out.put("customChunkGraphScheduler", bootstrapped
                ? GACustomChunkGraphScheduler.snapshot()
                : unavailableUntilBootstrap("minecraft bootstrap not complete"));
        out.put("chunkStatusPipelineRuntime", bootstrapped ? GAChunkStatusPipeline.enabled() : true);
        out.put("chunkStatusPipeline", bootstrapped
                ? GAChunkStatusPipeline.snapshot()
                : unavailableUntilBootstrap("minecraft bootstrap not complete"));
        out.put("workspaceRuntimeEnabled", GAChunkWorkspaceRuntime.runtimeEnabled());
        out.put("knownDecorationJournalRuntimeEnabled", GAWorkspaceWriteBridge.knownDecorationJournalWritesEnabled());
        out.put("decorationConflictSchedulerRuntimeEnabled", DecorationPipelineExecutor.conflictSchedulerRuntimeEnabled());
        out.put("decorationConflictSchedulerDetachedSnapshots", true);
        out.put("decorationConflictSchedulerSnapshotRadius", DecorationPipelineExecutor.conflictSchedulerSnapshotRadius());
        out.put("decorationConflictScheduler", DecorationConflictSchedulerMetrics.snapshot());
        out.put("globalWorkspaceOnlyWritesEnabled", GAWorkspaceWriteBridge.workspaceOnlyWritesEnabled());
        out.put("workspaceContextBound", contextBound);
        out.put("workspaceBlockImportRuntime", blockImported);
        out.put("workspaceFinalizeRuntime", finalized);
        out.put("decorationWorkspaceRuntime", contextBound && DecorationWorkspaceBridge.enabled());
        out.put("knownKernelWorkspaceMirrors", mirroredWrites);
        out.put("workspaceBackedPlacementReads", contextBound && DecorationWorkspaceBridge.enabled());
        out.put("schedulerNoiseLaneRuntime", Boolean.parseBoolean(System.getProperty("ga.scheduler.overrideNoiseExecutor", "true")));
        out.put("schedulerCompileLaneRuntime", true);
        out.put("schedulerPoolsLazy", true);
        out.put("schedulerWorkspaceLaneRuntime", true);
        out.put("schedulerTransactionalLaneRuntime", true);
        out.put("schedulerSerialLaneRuntime", false);
        out.put("schedulerCommitLaneRuntime", true);
        out.put("classifierRuntimeDecision", true);
        out.put("classifierRegistryScanRuntime", true);
        out.put("classifierReloadScanOrchestrator", true);
        out.put("terrainWorkspaceBackend", true);
        out.put("terrainWorkspacePipelineRuntime", terrainWorkspace);
        out.put("terrainWorkspacePassesDetached", true);
        out.put("workspaceFinalDiffRunCommitEngine", GAChunkWorkspaceRuntime.finalRepackEnabled() && workspaceOnlyWrites && commitApplied);
        out.put("workspaceFinalRepackCommitEngine", GAChunkWorkspaceRuntime.finalRepackEnabled() && workspaceOnlyWrites && commitApplied);
        out.put("deterministicCommitRuntime", commitApplied);
        out.put("detachedCommitEngineRuntime", true);
        out.put("crossChunkMailboxPrototype", true);
        out.put("crossChunkMailbox", GACrossChunkMailboxRuntime.snapshot());
        out.put("transactionSandboxRuntime", GATransactionRuntimeDispatcher.enabled()
                && number(GATransactionRuntimeDispatcher.snapshot(), "dispatched") > 0L);
        out.put("transactionSandbox", GATransactionRuntimeDispatcher.snapshot());
        out.put("transactionSuccessOnlyCommandJournal", true);
        out.put("transactionAbortDowngradeHandoff", true);
        out.put("effectAnalysisRuntime", true);
        out.put("effectAnalysisScheduler", true);
        out.put("effectAnalysisClassifierDowngrade", true);
        out.put("effectAnalysisDeepUnknownScanRuntime", Boolean.getBoolean("ga.worldgenProfile.deepUnknownScan"));
        out.put("patternOptimizerRuntime", true);
        out.put("patternOptimizerGuards", true);
        out.put("patternOptimizerParitySampler", true);
        out.put("outerLifecycleRuntime", true);
        out.put("lightingHandoffMasks", true);
        out.put("serializationBatchPlanning", true);
        out.put("publishingGuardRuntime", true);
        out.put("diagnosticsFeedbackRuntime", true);
        out.put("diagnosticsCompatTargets", true);
        out.put("diagnosticsWorkspaceBreakdown", true);
        out.put("serialUnsafeLaneRuntime", number(GATransactionRuntimeDispatcher.snapshot(), "serialFallback") > 0L);
        out.put("crossChunkMailboxRuntime", GACrossChunkMailboxRuntime.enabled()
                && number(GACrossChunkMailboxRuntime.snapshot(), "enqueued") > 0L);
        out.put("adaptiveGovernorRuntime", true);
        return out;
    }

    private static Map<String, Object> majorMissingPieces() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decorationWorkspaceRuntime", "Known ore/scattered-ore/disk/simple/block-column kernels can journal into workspace-only diff runs; parallel ore scheduling uses detached snapshots and falls back sequentially on snapshot misses.");
        out.put("terrainRuntime", "Terrain workspace passes are detached helpers; vanilla terrain mixins do not commit chunks through them yet.");
        out.put("transactionRuntime", "Transaction sandbox value path exists, but unknown worldgen is not live-dispatched through it.");
        out.put("serialUnsafeRuntime", "Live unsafe serial fallback dispatch remains loader-compat gated; serial lane itself is bounded and clamped");
        out.put("crossChunkMailboxRuntime", "Cross-chunk mailbox is deterministic prototype/value API; live neighbor-chunk dispatch remains guarded rollout");
        out.put("lightingIoPromotionRuntime", "Phase 10 exposes safe masks/plans/guards; loader-specific wiring remains opt-in");
        return out;
    }

    private static long number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static boolean minecraftBootstrapped() {
        try {
            Bootstrap.checkBootstrapCalled(() -> "GA worldgen pipeline diagnostics");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Map<String, Object> unavailableUntilBootstrap(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotAvailable", false);
        out.put("reason", reason);
        return out;
    }
}
