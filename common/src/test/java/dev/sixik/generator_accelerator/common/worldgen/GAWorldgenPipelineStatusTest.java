package dev.sixik.generator_accelerator.common.worldgen;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAWorldgenPipelineStatusTest {
    @Test
    void snapshotReportsPhaseZeroToTwoRuntimeGates() {
        Map<String, Object> snapshot = GAWorldgenPipelineStatus.snapshot();

        assertEquals("adaptive-worldgen-pipeline-status-v1", snapshot.get("schema"));
        assertTrue(snapshot.containsKey("phaseCompletionPercent"));
        assertTrue(snapshot.containsKey("phaseStatus"));
        assertTrue(snapshot.containsKey("runtimeGates"));

        @SuppressWarnings("unchecked")
        Map<String, Object> completion = (Map<String, Object>) snapshot.get("phaseCompletionPercent");
        assertEquals(100, completion.get("phase0Contracts"));
        assertEquals(100, completion.get("phase1WorkspaceSkeleton"));
        assertEquals(100, completion.get("phase2UnifiedScheduler"));
        assertEquals(100, completion.get("phase3TerrainWorkspace"));
        assertEquals(100, completion.get("phase4Classifier"));
        assertEquals(100, completion.get("phase5CommitEngine"));
        assertEquals(100, completion.get("phase6DecorationWorkspace"));
        assertEquals(100, completion.get("phase7TransactionSandbox"));
        assertEquals(100, completion.get("phase8EffectAnalysis"));
        assertEquals(100, completion.get("phase9PatternOptimizer"));
        assertEquals(100, completion.get("phase10OuterLifecycle"));
        assertEquals(100, completion.get("phase11Diagnostics"));

        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeGates = (Map<String, Object>) snapshot.get("runtimeGates");
        assertEquals(false, runtimeGates.get("schedulerNoiseLaneRuntime"));
        assertEquals(false, runtimeGates.get("workspaceContextBound"));
        assertEquals(false, runtimeGates.get("workspaceBlockImportRuntime"));
        assertEquals(false, runtimeGates.get("workspaceFinalizeRuntime"));
        assertEquals(false, runtimeGates.get("decorationWorkspaceRuntime"));
        assertEquals(false, runtimeGates.get("knownKernelWorkspaceMirrors"));
        assertEquals(false, runtimeGates.get("workspaceBackedPlacementReads"));
        assertEquals(true, runtimeGates.get("schedulerWorkspaceLaneRuntime"));
        assertEquals(true, runtimeGates.get("schedulerTransactionalLaneRuntime"));
        assertEquals(true, runtimeGates.get("schedulerCommitLaneRuntime"));
        assertEquals(true, runtimeGates.get("adaptiveGovernorRuntime"));
        assertEquals(true, runtimeGates.get("terrainWorkspaceBackend"));
        assertEquals(false, runtimeGates.get("terrainWorkspacePipelineRuntime"));
        assertEquals(true, runtimeGates.get("terrainWorkspacePassesDetached"));
        assertEquals(true, runtimeGates.get("classifierRuntimeDecision"));
        assertEquals(true, runtimeGates.get("classifierRegistryScanRuntime"));
        assertEquals(true, runtimeGates.get("classifierReloadScanOrchestrator"));
        assertEquals(false, runtimeGates.get("workspaceFinalRepackCommitEngine"));
        assertEquals(false, runtimeGates.get("deterministicCommitRuntime"));
        assertEquals(true, runtimeGates.get("detachedCommitEngineRuntime"));
        assertEquals(true, runtimeGates.get("crossChunkMailboxPrototype"));
        assertEquals(false, runtimeGates.get("transactionSandboxRuntime"));
        assertEquals(true, runtimeGates.get("transactionSuccessOnlyCommandJournal"));
        assertEquals(true, runtimeGates.get("transactionAbortDowngradeHandoff"));
        assertEquals(true, runtimeGates.get("effectAnalysisRuntime"));
        assertEquals(true, runtimeGates.get("effectAnalysisScheduler"));
        assertEquals(true, runtimeGates.get("effectAnalysisClassifierDowngrade"));
        assertEquals(true, runtimeGates.get("patternOptimizerRuntime"));
        assertEquals(true, runtimeGates.get("patternOptimizerGuards"));
        assertEquals(true, runtimeGates.get("patternOptimizerParitySampler"));
        assertEquals(true, runtimeGates.get("outerLifecycleRuntime"));
        assertEquals(true, runtimeGates.get("lightingHandoffMasks"));
        assertEquals(true, runtimeGates.get("serializationBatchPlanning"));
        assertEquals(true, runtimeGates.get("publishingGuardRuntime"));
        assertEquals(true, runtimeGates.get("diagnosticsFeedbackRuntime"));
        assertEquals(true, runtimeGates.get("diagnosticsCompatTargets"));
        assertEquals(true, runtimeGates.get("diagnosticsWorkspaceBreakdown"));
        assertEquals(false, runtimeGates.get("schedulerSerialLaneRuntime"));
        assertEquals(false, runtimeGates.get("serialUnsafeLaneRuntime"));
        assertEquals(false, runtimeGates.get("crossChunkMailboxRuntime"));
    }
}
