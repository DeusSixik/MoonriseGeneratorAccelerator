package dev.sixik.generator_accelerator.common.features.pipeline;

import java.util.Map;

/**
 * Stable value snapshot of decoration pipeline behavior that optimization work
 * must preserve.
 */
public final class DecorationPipelineContract {
    public static final String SCHEMA = "decoration-pipeline-contract-v1";
    public static final String STEP_ORDER = "structures-before-features-per-step";
    public static final String FEATURE_ORDER = "ascending-feature-index";
    public static final String SEED_SHAPE = "setFeatureSeed(decorationSeed, unitIndex, step)";
    public static final String QUARANTINE_FALLBACK_SCOPE = "per-placed-feature-instance";
    public static final String DIRECT_JOURNAL_COLLISION = "first-write-wins";
    public static final String WORKSPACE_OWNERSHIP =
            "live-decoration-uses-vanilla-chunk-ownership";
    public static final String WORKSPACE_WRITE_SCOPE =
            "known-decoration-kernels-write-vanilla-immediately;no-final-workspace-repack";
    public static final String WORKSPACE_READ_SCOPE =
            "placement-context-uses-live-world-reads;current-workspace-bridge-disabled-by-default";

    private DecorationPipelineContract() {
    }

    public static Map<String, String> snapshot() {
        return Map.of(
                "schema", SCHEMA,
                "stepOrder", STEP_ORDER,
                "featureOrder", FEATURE_ORDER,
                "seedShape", SEED_SHAPE,
                "quarantineFallbackScope", QUARANTINE_FALLBACK_SCOPE,
                "directJournalCollision", DIRECT_JOURNAL_COLLISION,
                "workspaceOwnership", WORKSPACE_OWNERSHIP,
                "workspaceWriteScope", WORKSPACE_WRITE_SCOPE,
                "workspaceReadScope", WORKSPACE_READ_SCOPE
        );
    }
}
