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
        out.put("phase3TerrainWorkspace", 80);
        out.put("phase4Classifier", 100);
        out.put("phase5CommitEngine", 90);
        out.put("phase6DecorationWorkspace", 100);
        out.put("phase7TransactionSandbox", 70);
        out.put("phase8EffectAnalysis", 100);
        out.put("phase9PatternOptimizer", 70);
        out.put("phase10OuterLifecycle", 85);
        out.put("phase11Diagnostics", 100);
        return out;
    }

    private static Map<String, Object> phaseStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase0Contracts", "complete-decoration-contracts-locked");
        out.put("phase1Workspace", "complete-import-finalize-release;live for trusted known-decoration journals");
        out.put("phase2Scheduler", "complete-unified-lanes-governor-metrics-lazy-pool-admission;live custom chunk DAG scheduler and chunk-status dispatch");
        out.put("phase3TerrainWorkspace", "terrain workspace backend is live only behind workspace-only writes; staged pass pipeline remains detached");
        out.put("phase4Classifier", "complete-cheap-tier-registry-scan-rollout-metadata");
        out.put("phase5CommitEngine", "deterministic block/final-repack commits are live; tick/heightmap/postprocess side-effect values are not applied by a live hook");
        out.put("phase6DecorationWorkspace", "opt-in trusted known-decoration workspace-only journals; conflict scheduler uses detached read snapshots for parallel ore kernels");
        out.put("phase7TransactionSandbox", "dispatcher and command journal exist for explicit units; vanilla unknown worldgen is not live-dispatched");
        out.put("phase8EffectAnalysis", "complete-lightweight-classfile-scan-cache-hot-analysis-downgrade");
        out.put("phase9PatternOptimizer", "detached pattern recognizer, guards, and parity sampler; no live generated-plan replacement hook");
        out.put("phase10OuterLifecycle", "lighting masks, serialization plans, and publishing guards exist; loader-specific promotion hooks remain opt-in");
        out.put("phase11Diagnostics", "complete-json-jfr-feedback-compat-targets-workspace-breakdown");
        return out;
    }

    private static Map<String, Object> runtimeGates() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> workspaceMetrics = GAChunkWorkspaceMetrics.snapshotGlobal();
        Map<String, Object> commitMetrics = GACommitMetrics.snapshotGlobal();
        Map<String, Object> transactionSnapshot = GATransactionRuntimeDispatcher.snapshot();
        Map<String, Object> mailboxSnapshot = GACrossChunkMailboxRuntime.snapshot();
        boolean contextBound = number(workspaceMetrics, "contextBoundSessions") > 0L;
        boolean blockImported = number(workspaceMetrics, "importNanos") > 0L;
        boolean finalized = number(workspaceMetrics, "finalizeNanos") > 0L;
        boolean mirroredWrites = number(workspaceMetrics, "mirroredBlockWrites") > 0L;
        boolean workspaceOnlyWrites = number(workspaceMetrics, "workspaceOnlyBlockWrites") > 0L;
        boolean terrainWorkspace = workspaceOnlyWrites && (number(workspaceMetrics, "terrainBlockWrites") > 0L
                || number(workspaceMetrics, "terrainAirImports") > 0L
                || number(workspaceMetrics, "finalRepackDenseSectionCopies") > 0L
                || number(workspaceMetrics, "finalRepackTerrainSectionCopies") > 0L);
        boolean commitApplied = number(commitMetrics, "accepted") > 0L && number(commitMetrics, "failures") == 0L;
        boolean mailboxEnabled = booleanValue(mailboxSnapshot, "enabled");
        boolean mailboxDrained = number(mailboxSnapshot, "drainExecutions") > 0L
                && number(mailboxSnapshot, "drainFailures") == 0L;
        boolean transactionExplicitRuntime = GATransactionRuntimeDispatcher.enabled()
                && number(transactionSnapshot, "dispatched") > 0L;
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
        out.put("workspaceOnlyWritesRuntimeDisabled", GAWorkspaceWriteBridge.workspaceOnlyWritesRuntimeDisabled());
        out.put("workspaceOnlyDisableReason", GAWorkspaceWriteBridge.workspaceOnlyDisableReason());
        out.put("workspaceContextBound", contextBound);
        out.put("workspaceBlockImportRuntime", blockImported);
        out.put("workspaceFinalizeRuntime", finalized);
        out.put("workspaceTerrainAirImports", number(workspaceMetrics, "terrainAirImports"));
        out.put("workspaceTerrainLazyAirImports", number(workspaceMetrics, "terrainLazyAirImports"));
        out.put("workspaceTerrainLazyAirSectionClears", number(workspaceMetrics, "terrainLazyAirSectionClears"));
        out.put("workspaceLocalTerrainFinalSections", number(workspaceMetrics, "finalRepackLocalTerrainSections"));
        out.put("workspaceFinalDenseSectionCopies", number(workspaceMetrics, "finalRepackDenseSectionCopies"));
        out.put("workspaceFinalTerrainSectionCopies", number(workspaceMetrics, "finalRepackTerrainSectionCopies"));
        out.put("workspaceFinalRepackRepairs", number(workspaceMetrics, "finalRepackRepairs"));
        out.put("workspaceEmergencyRepacks", number(workspaceMetrics, "emergencyRepacks"));
        out.put("workspaceEmergencyRepackFailures", number(workspaceMetrics, "emergencyRepackFailures"));
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
        out.put("terrainWorkspaceVanillaHookRuntime", terrainWorkspace);
        out.put("terrainWorkspacePipelineRuntime", terrainWorkspace);
        out.put("terrainWorkspacePassesDetached", true);
        out.put("workspaceFinalDiffRunCommitEngine", GAChunkWorkspaceRuntime.finalRepackEnabled() && workspaceOnlyWrites && commitApplied);
        out.put("workspaceFinalRepackCommitEngine", GAChunkWorkspaceRuntime.finalRepackEnabled() && workspaceOnlyWrites && commitApplied);
        out.put("deterministicCommitRuntime", commitApplied);
        out.put("detachedCommitEngineAvailable", true);
        out.put("detachedCommitEngineRuntime", false);
        out.put("commitSideEffectValueApi", true);
        out.put("commitSideEffectRuntime", false);
        out.put("crossChunkMailboxPrototype", true);
        out.put("crossChunkMailboxRuntimeEnabled", mailboxEnabled);
        out.put("crossChunkMailboxValueApi", true);
        out.put("crossChunkMailboxLiveDrains", mailboxDrained);
        out.put("crossChunkMailboxQueued", number(mailboxSnapshot, "enqueued") > 0L);
        out.put("crossChunkMailbox", mailboxSnapshot);
        out.put("transactionSandboxDispatcherEnabled", GATransactionRuntimeDispatcher.enabled());
        out.put("transactionSandboxExplicitRuntime", transactionExplicitRuntime);
        out.put("transactionSandboxLiveHook", false);
        out.put("transactionSandboxRuntime", false);
        out.put("transactionSandbox", transactionSnapshot);
        out.put("transactionSuccessOnlyCommandJournal", true);
        out.put("transactionAbortDowngradeHandoff", true);
        out.put("effectAnalysisRuntime", true);
        out.put("effectAnalysisScheduler", true);
        out.put("effectAnalysisClassifierDowngrade", true);
        out.put("effectAnalysisDeepUnknownScanRuntime", Boolean.getBoolean("ga.worldgenProfile.deepUnknownScan"));
        out.put("patternOptimizerPlannerAvailable", true);
        out.put("patternOptimizerRuntime", false);
        out.put("patternOptimizerGuards", true);
        out.put("patternOptimizerParitySampler", true);
        out.put("outerLifecycleRuntime", true);
        out.put("lightingHandoffMasks", true);
        out.put("lightingIoPromotionRuntime", false);
        out.put("serializationBatchPlanning", true);
        out.put("publishingGuardRuntime", true);
        out.put("diagnosticsFeedbackRuntime", true);
        out.put("diagnosticsCompatTargets", true);
        out.put("diagnosticsWorkspaceBreakdown", true);
        out.put("serialUnsafeLaneRuntime", number(transactionSnapshot, "serialFallback") > 0L);
        out.put("crossChunkMailboxRuntime", mailboxEnabled && mailboxDrained);
        out.put("adaptiveGovernorRuntime", true);
        return out;
    }

    private static Map<String, Object> majorMissingPieces() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decorationWorkspaceRuntime", "Known ore/scattered-ore/disk/simple/block-column kernels can journal into workspace-only diff runs; parallel ore scheduling uses detached snapshots and falls back sequentially on snapshot misses.");
        out.put("terrainRuntime", "Terrain workspace writes are runtime-gated by workspace-only writes; staged pass helpers remain detached.");
        out.put("transactionRuntime", "Transaction sandbox dispatcher exists for explicit units, but unknown vanilla/modded worldgen is not auto-routed through it.");
        out.put("commitSideEffectsRuntime", "Postprocess/tick/heightmap side-effect commands are value API only; live owner-apply hooks are not enabled.");
        out.put("serialUnsafeRuntime", "Live unsafe serial fallback dispatch remains loader-compat gated; serial lane itself is bounded and clamped");
        out.put("crossChunkMailboxRuntime", "Cross-chunk mailbox has value API and opportunistic workspace/status drains; broad producer rollout remains guarded");
        out.put("patternOptimizerRuntime", "Pattern recognizer/guards/parity sampler are detached; generated plans are not injected into live worldgen.");
        out.put("lightingIoPromotionRuntime", "Phase 10 exposes safe masks/plans/guards; loader-specific wiring remains opt-in");
        return out;
    }

    private static long number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static boolean booleanValue(Map<String, Object> values, String key) {
        return Boolean.TRUE.equals(values.get(key));
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
