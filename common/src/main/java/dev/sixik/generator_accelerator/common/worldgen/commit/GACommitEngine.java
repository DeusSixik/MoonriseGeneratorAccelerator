package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Detached helper for deterministic resolve-then-replay commit execution.
 */
public final class GACommitEngine {
    private GACommitEngine() {
    }

    public static <T> GACommitExecution<T> execute(
            GACommitBatch<T> batch,
            GACommitCollisionPolicy policy,
            CommitApplier<T> applier
    ) {
        if (batch == null) {
            throw new NullPointerException("batch");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        if (applier == null) {
            throw new NullPointerException("applier");
        }

        long startNanos = System.nanoTime();
        GACommitBatch.GAResolvedCommitBatch<T> resolved = batch.resolve(policy);
        List<GACommitFailure<T>> failures = null;
        for (GACommitCommand<T> command : resolved.accepted()) {
            try {
                applier.apply(command);
            } catch (Exception exception) {
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(new GACommitFailure<>(command, exception));
            }
        }
        int failureCount = failures == null ? 0 : failures.size();
        long executionNanos = Math.max(0L, System.nanoTime() - startNanos);
        GACommitBatchStats stats = resolved.stats();
        GACommitMetrics metrics = new GACommitMetrics(
                1,
                stats.inputCount(),
                stats.acceptedCount(),
                stats.rejectedCount(),
                stats.collisionCount(),
                executionNanos,
                failureCount
        );
        GACommitMetrics.record(metrics);
        return new GACommitExecution<>(resolved, metrics, failures == null ? List.of() : failures);
    }

    public static <T> GACommitPlan<T> plan(
            GACommitBatch<T> batch,
            GACommitCollisionPolicy policy,
            GACommitConflictGroups.GACommitConflictGranularity granularity
    ) {
        return GACommitPlan.of(batch, policy, granularity);
    }

    public static <T> GACommitFinalizePlan<T> finalizePlan(GACommitPlan<T> plan) {
        return GACommitFinalizePlan.of(plan);
    }

    public static <T> GACommitExecution<T> replayFinalized(
            GACommitFinalizePlan<T> finalizePlan,
            CommitApplier<T> applier
    ) {
        if (finalizePlan == null) {
            throw new NullPointerException("finalizePlan");
        }
        if (applier == null) {
            throw new NullPointerException("applier");
        }

        long startNanos = System.nanoTime();
        List<GACommitFailure<T>> failures = null;
        for (GACommitCommand<T> command : finalizePlan.replayCommands()) {
            try {
                applier.apply(command);
            } catch (Exception exception) {
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(new GACommitFailure<>(command, exception));
            }
        }
        int failureCount = failures == null ? 0 : failures.size();
        long executionNanos = Math.max(0L, System.nanoTime() - startNanos);
        GACommitBatchStats stats = finalizePlan.stats();
        GACommitMetrics metrics = new GACommitMetrics(
                1,
                stats.inputCount(),
                stats.acceptedCount(),
                stats.rejectedCount(),
                stats.collisionCount(),
                executionNanos,
                failureCount
        );
        GACommitMetrics.record(metrics);
        return new GACommitExecution<>(finalizePlan.plan().resolved(), metrics, failures == null ? List.of() : failures);
    }

    public static <T> GACommitReplayPlan<T> replayByChunk(
            Collection<GACommitCommand<T>> commands,
            CommitApplier<T> applier
    ) {
        return replayByChunk(commands, GACommitCollisionPolicy.FIRST_WRITE_WINS, applier);
    }

    public static <T> GACommitReplayPlan<T> replayByChunk(
            Collection<GACommitCommand<T>> commands,
            GACommitCollisionPolicy policy,
            CommitApplier<T> applier
    ) {
        return replayGrouped(commands, policy, GACommitConflictGroups.GACommitConflictGranularity.CHUNK, applier);
    }

    public static <T> GACommitReplayPlan<T> replayBySection(
            Collection<GACommitCommand<T>> commands,
            CommitApplier<T> applier
    ) {
        return replayBySection(commands, GACommitCollisionPolicy.FIRST_WRITE_WINS, applier);
    }

    public static <T> GACommitReplayPlan<T> replayBySection(
            Collection<GACommitCommand<T>> commands,
            GACommitCollisionPolicy policy,
            CommitApplier<T> applier
    ) {
        return replayGrouped(commands, policy, GACommitConflictGroups.GACommitConflictGranularity.BLOCK_SECTION, applier);
    }

    private static <T> GACommitReplayPlan<T> replayGrouped(
            Collection<GACommitCommand<T>> commands,
            GACommitCollisionPolicy policy,
            GACommitConflictGroups.GACommitConflictGranularity granularity,
            CommitApplier<T> applier
    ) {
        if (commands == null) {
            throw new NullPointerException("commands");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        if (granularity == null) {
            throw new NullPointerException("granularity");
        }
        if (applier == null) {
            throw new NullPointerException("applier");
        }

        GACommitBatch<T> batch = GACommitBatch.of(commands);
        GACommitPlan<T> plan = GACommitPlan.of(batch, policy, granularity);
        List<GACommitReplayPlan.GACommitReplayGroup<T>> replayGroups = new ArrayList<>();
        List<GACommitFailure<T>> failures = new ArrayList<>();
        GACommitMetrics aggregate = GACommitMetrics.empty();

        for (GACommitConflictGroups.GACommitConflictGroup<T> group : plan.conflictGroups().groups()) {
            GACommitExecution<T> execution = execute(GACommitBatch.of(group.commands()), policy, applier);
            aggregate = aggregate.plus(execution.metrics());
            failures.addAll(execution.failures());
            replayGroups.add(new GACommitReplayPlan.GACommitReplayGroup<>(
                    group.key(),
                    execution.resolved(),
                    execution.metrics(),
                    execution.failures()
            ));
        }

        GACommitReplayPlan.GACommitReplayStats stats = new GACommitReplayPlan.GACommitReplayStats(
                replayGroups.size(),
                failures.size(),
                plan.resolved().stats()
        );
        return new GACommitReplayPlan<>(plan, replayGroups, aggregate, failures, stats);
    }

    public record GACommitExecution<T>(
            GACommitBatch.GAResolvedCommitBatch<T> resolved,
            GACommitMetrics metrics,
            List<GACommitFailure<T>> failures
    ) {
    }

    public record GACommitFailure<T>(
            GACommitCommand<T> command,
            Exception exception
    ) {
    }
}
