package dev.sixik.generator_accelerator.common.worldgen;

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
        out.put("summary", "Adaptive worldgen infrastructure is staged, but live decoration keeps vanilla chunk ownership; workspace import/final-repack is disabled for parity.");
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
        out.put("phase1Workspace", "complete-skeleton-detached-import-finalize-release;not bound to live decoration");
        out.put("phase2Scheduler", "complete-unified-lanes-governor-metrics-lazy-pool-admission");
        out.put("phase3TerrainWorkspace", "complete-staged-density-aquifer-biome-surface-carver-detached-pipeline;not wired to vanilla terrain");
        out.put("phase4Classifier", "complete-cheap-tier-registry-scan-rollout-metadata");
        out.put("phase5CommitEngine", "complete-detached-deterministic-plans-fast-collision-stats;live chunk repack disabled");
        out.put("phase6DecorationWorkspace", "guarded-off-live-decoration-to-preserve-structure-and-feature-writes");
        out.put("phase7TransactionSandbox", "complete-detached-transaction-lane-command-journal-handoff;not live-dispatched");
        out.put("phase8EffectAnalysis", "complete-lightweight-classfile-scan-cache-hot-analysis-downgrade");
        out.put("phase9PatternOptimizer", "complete-detached-pattern-recognizer-guards-parity-metrics");
        out.put("phase10OuterLifecycle", "complete-lighting-mask-serialization-plan-publishing-guards");
        out.put("phase11Diagnostics", "complete-json-jfr-feedback-compat-targets-workspace-breakdown");
        return out;
    }

    private static Map<String, Object> runtimeGates() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceContextBound", false);
        out.put("workspaceBlockImportRuntime", false);
        out.put("workspaceFinalizeRuntime", false);
        out.put("decorationWorkspaceRuntime", false);
        out.put("knownKernelWorkspaceMirrors", false);
        out.put("workspaceBackedPlacementReads", false);
        out.put("schedulerNoiseLaneRuntime", Boolean.getBoolean("ga.scheduler.overrideNoiseExecutor"));
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
        out.put("terrainWorkspacePipelineRuntime", false);
        out.put("terrainWorkspacePassesDetached", true);
        out.put("workspaceFinalRepackCommitEngine", false);
        out.put("deterministicCommitRuntime", false);
        out.put("detachedCommitEngineRuntime", true);
        out.put("crossChunkMailboxPrototype", true);
        out.put("transactionSandboxRuntime", false);
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
        out.put("serialUnsafeLaneRuntime", false);
        out.put("crossChunkMailboxRuntime", false);
        out.put("adaptiveGovernorRuntime", true);
        return out;
    }

    private static Map<String, Object> majorMissingPieces() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decorationWorkspaceRuntime", "Live decoration workspace import/repack disabled: vanilla structures, trees, decorators, and mod features mutate chunks directly and cannot be safely overwritten by a stale snapshot.");
        out.put("terrainRuntime", "Terrain workspace passes are detached helpers; vanilla terrain mixins do not commit chunks through them yet.");
        out.put("transactionRuntime", "Transaction sandbox value path exists, but unknown worldgen is not live-dispatched through it.");
        out.put("serialUnsafeRuntime", "Live unsafe serial fallback dispatch remains loader-compat gated; serial lane itself is bounded and clamped");
        out.put("crossChunkMailboxRuntime", "Cross-chunk mailbox is deterministic prototype/value API; live neighbor-chunk dispatch remains guarded rollout");
        out.put("lightingIoPromotionRuntime", "Phase 10 exposes safe masks/plans/guards; loader-specific wiring remains opt-in");
        return out;
    }
}
