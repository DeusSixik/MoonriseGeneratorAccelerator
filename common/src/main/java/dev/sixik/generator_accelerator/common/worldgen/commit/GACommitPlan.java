package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic resolve snapshot for diagnostics and later replay.
 */
public record GACommitPlan<T>(
        GACommitBatch.GAResolvedCommitBatch<T> resolved,
        GACommitConflictGroups<T> conflictGroups,
        List<GACommitMailbox.GACommitMailboxDrain<T>> mailboxOutputs,
        GACommitPlanStats stats
) {
    public static <T> GACommitPlan<T> of(
            GACommitBatch<T> batch,
            GACommitCollisionPolicy policy,
            GACommitConflictGroups.GACommitConflictGranularity granularity
    ) {
        if (batch == null) {
            throw new NullPointerException("batch");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        if (granularity == null) {
            throw new NullPointerException("granularity");
        }
        GACommitBatch.GAResolvedCommitBatch<T> resolved = batch.resolve(policy);
        GACommitConflictGroups<T> groups = GACommitConflictGroups.analyze(batch.commands(), granularity);
        return new GACommitPlan<>(resolved, groups, List.of(), GACommitPlanStats.from(resolved, groups, 0));
    }

    public static <T> GACommitPlan<T> ofMailboxOutputs(
            List<GACommitMailbox.GACommitMailboxDrain<T>> mailboxOutputs,
            GACommitConflictGroups.GACommitConflictGranularity granularity
    ) {
        if (mailboxOutputs == null) {
            throw new NullPointerException("mailboxOutputs");
        }
        if (granularity == null) {
            throw new NullPointerException("granularity");
        }
        List<GACommitCommand<T>> accepted = new ArrayList<>();
        List<GACommitCommand<T>> rejected = new ArrayList<>();
        int inputCount = 0;
        int collisionCount = 0;
        for (GACommitMailbox.GACommitMailboxDrain<T> output : mailboxOutputs) {
            if (output == null) {
                throw new NullPointerException("mailboxOutput");
            }
            accepted.addAll(output.resolved().accepted());
            rejected.addAll(output.resolved().rejected());
            GACommitBatchStats stats = output.resolved().stats();
            inputCount += stats.inputCount();
            collisionCount += stats.collisionCount();
        }
        List<GACommitCommand<T>> all = new ArrayList<>(accepted);
        all.addAll(rejected);
        GACommitBatch.GAResolvedCommitBatch<T> resolved = new GACommitBatch.GAResolvedCommitBatch<>(
                accepted,
                rejected,
                new GACommitBatchStats(inputCount, accepted.size(), rejected.size(), collisionCount)
        );
        GACommitConflictGroups<T> groups = GACommitConflictGroups.analyze(all, granularity);
        return new GACommitPlan<>(
                resolved,
                groups,
                mailboxOutputs,
                GACommitPlanStats.from(resolved, groups, mailboxOutputs.size())
        );
    }

    public record GACommitPlanStats(
            GACommitBatchStats batchStats,
            GACommitConflictGroups.GACommitConflictStats conflictStats,
            int mailboxTargetCount
    ) {
        private static <T> GACommitPlanStats from(
                GACommitBatch.GAResolvedCommitBatch<T> resolved,
                GACommitConflictGroups<T> groups,
                int mailboxTargetCount
        ) {
            return new GACommitPlanStats(resolved.stats(), groups.stats(), mailboxTargetCount);
        }
    }
}
