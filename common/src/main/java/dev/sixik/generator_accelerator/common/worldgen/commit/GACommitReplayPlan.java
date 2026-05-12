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
    public GACommitReplayPlan {
        if (plan == null) {
            throw new NullPointerException("plan");
        }
        groups = groups == null ? List.of() : List.copyOf(groups);
        if (metrics == null) {
            throw new NullPointerException("metrics");
        }
        failures = failures == null ? List.of() : List.copyOf(failures);
        if (stats == null) {
            throw new NullPointerException("stats");
        }
    }

    public record GACommitReplayGroup<T>(
            GACommitConflictGroups.GACommitConflictKey key,
            GACommitBatch.GAResolvedCommitBatch<T> resolved,
            GACommitMetrics metrics,
            List<GACommitEngine.GACommitFailure<T>> failures
    ) {
        public GACommitReplayGroup {
            if (key == null) {
                throw new NullPointerException("key");
            }
            if (resolved == null) {
                throw new NullPointerException("resolved");
            }
            if (metrics == null) {
                throw new NullPointerException("metrics");
            }
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    public record GACommitReplayStats(
            int groupCount,
            int failureCount,
            GACommitBatchStats batchStats
    ) {
        public GACommitReplayStats {
            if (groupCount < 0) {
                throw new IllegalArgumentException("groupCount must be non-negative");
            }
            if (failureCount < 0) {
                throw new IllegalArgumentException("failureCount must be non-negative");
            }
            if (batchStats == null) {
                throw new NullPointerException("batchStats");
            }
        }
    }
}
