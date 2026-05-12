package dev.sixik.generator_accelerator.common.features.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecorationPipelineContractTest {
    @Test
    void snapshotLocksCurrentDecorationContracts() {
        Map<String, String> snapshot = DecorationPipelineContract.snapshot();

        assertEquals("decoration-pipeline-contract-v1", snapshot.get("schema"));
        assertEquals("structures-before-features-per-step", snapshot.get("stepOrder"));
        assertEquals("ascending-feature-index", snapshot.get("featureOrder"));
        assertEquals("setFeatureSeed(decorationSeed, unitIndex, step)", snapshot.get("seedShape"));
        assertEquals("per-placed-feature-instance", snapshot.get("quarantineFallbackScope"));
        assertEquals("first-write-wins", snapshot.get("directJournalCollision"));
        assertEquals("live-decoration-uses-vanilla-chunk-ownership",
                snapshot.get("workspaceOwnership"));
        assertEquals("known-decoration-kernels-write-vanilla-immediately;no-final-workspace-repack",
                snapshot.get("workspaceWriteScope"));
        assertEquals("placement-context-uses-live-world-reads;current-workspace-bridge-disabled-by-default",
                snapshot.get("workspaceReadScope"));
    }

    @Test
    void constantsMatchSnapshotValues() {
        Map<String, String> snapshot = DecorationPipelineContract.snapshot();

        assertEquals(DecorationPipelineContract.SCHEMA, snapshot.get("schema"));
        assertEquals(DecorationPipelineContract.STEP_ORDER, snapshot.get("stepOrder"));
        assertEquals(DecorationPipelineContract.FEATURE_ORDER, snapshot.get("featureOrder"));
        assertEquals(DecorationPipelineContract.SEED_SHAPE, snapshot.get("seedShape"));
        assertEquals(DecorationPipelineContract.QUARANTINE_FALLBACK_SCOPE, snapshot.get("quarantineFallbackScope"));
        assertEquals(DecorationPipelineContract.DIRECT_JOURNAL_COLLISION, snapshot.get("directJournalCollision"));
        assertEquals(DecorationPipelineContract.WORKSPACE_OWNERSHIP, snapshot.get("workspaceOwnership"));
        assertEquals(DecorationPipelineContract.WORKSPACE_WRITE_SCOPE, snapshot.get("workspaceWriteScope"));
        assertEquals(DecorationPipelineContract.WORKSPACE_READ_SCOPE, snapshot.get("workspaceReadScope"));
    }
}
