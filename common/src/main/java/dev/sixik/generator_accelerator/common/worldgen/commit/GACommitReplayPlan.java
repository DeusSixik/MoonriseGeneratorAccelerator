package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.List;

/**
 * Deterministic grouped replay result, including all failures.
 */
public record GACommitReplayPlan<T>(
        GACommitPlan<T> plan,
        List<GACommitReplayGroup<T>> groups,
        GACommitMetrics metrics,
        List<GACommitEngine.GACommitFailure<T>> failures,
        GACommitReplayStats stats
) {
    public record GACommitReplayGroup<T>(
            GACommitConflictGroups.GACommitConflictKey key,
            GACommitBatch.GAResolvedCommitBatch<T> resolved,
            GACommitMetrics metrics,
            List<GACommitEngine.GACommitFailure<T>> failures
    ) {
    }

    public record GACommitReplayStats(
            int groupCount,
            int failureCount,
            GACommitBatchStats batchStats
    ) {
    }
}
