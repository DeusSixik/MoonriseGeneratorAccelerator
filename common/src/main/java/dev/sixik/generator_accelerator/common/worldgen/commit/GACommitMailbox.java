package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prototype per-target chunk mailbox for cross-chunk commit handoff.
 */
public final class GACommitMailbox<T> {
    private final Map<GAChunkPosition, List<GACommitCommand<T>>> queues = new TreeMap<>();

    public void enqueue(GACommitCommand<T> command) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        queues.computeIfAbsent(GAChunkPosition.fromBlock(command.position()), ignored -> new ArrayList<>())
                .add(command);
    }

    public void enqueueAll(Collection<GACommitCommand<T>> commands) {
        if (commands == null) {
            throw new NullPointerException("commands");
        }
        for (GACommitCommand<T> command : commands) {
            enqueue(command);
        }
    }

    public boolean hasQueued(GAChunkPosition targetChunk) {
        if (targetChunk == null) {
            throw new NullPointerException("targetChunk");
        }
        List<GACommitCommand<T>> commands = queues.get(targetChunk);
        return commands != null && !commands.isEmpty();
    }

    public int queuedCommandCount(GAChunkPosition targetChunk) {
        if (targetChunk == null) {
            throw new NullPointerException("targetChunk");
        }
        List<GACommitCommand<T>> commands = queues.get(targetChunk);
        return commands == null ? 0 : commands.size();
    }

    public GACommitBatch.GAResolvedCommitBatch<T> drain(
            GAChunkPosition targetChunk,
            GACommitCollisionPolicy policy
    ) {
        if (targetChunk == null) {
            throw new NullPointerException("targetChunk");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        List<GACommitCommand<T>> commands = queues.remove(targetChunk);
        if (commands == null) {
            return GACommitBatch.<T>empty().resolve(policy);
        }
        return GACommitBatch.of(commands).resolve(policy);
    }

    public List<GACommitCommand<T>> drainCommands(GAChunkPosition targetChunk) {
        if (targetChunk == null) {
            throw new NullPointerException("targetChunk");
        }
        List<GACommitCommand<T>> commands = queues.remove(targetChunk);
        if (commands == null) {
            return List.of();
        }
        List<GACommitCommand<T>> ordered = new ArrayList<>(commands);
        ordered.sort(GACommitCommand::compareTo);
        return List.copyOf(ordered);
    }

    public List<GACommitMailboxDrain<T>> drainAll(GACommitCollisionPolicy policy) {
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        List<GACommitMailboxDrain<T>> drained = new ArrayList<>();
        for (Map.Entry<GAChunkPosition, List<GACommitCommand<T>>> entry : queues.entrySet()) {
            GACommitBatch.GAResolvedCommitBatch<T> resolved = GACommitBatch.of(entry.getValue()).resolve(policy);
            drained.add(new GACommitMailboxDrain<>(entry.getKey(), resolved));
        }
        queues.clear();
        return drained;
    }

    public GACommitPlan<T> drainAllPlan(
            GACommitCollisionPolicy policy,
            GACommitConflictGroups.GACommitConflictGranularity granularity
    ) {
        return GACommitPlan.ofMailboxOutputs(drainAll(policy), granularity);
    }

    public GACommitMailboxExecution<T> executeAll(
            GACommitCollisionPolicy policy,
            CommitApplier<T> applier
    ) {
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        if (applier == null) {
            throw new NullPointerException("applier");
        }

        long startNanos = System.nanoTime();
        List<GACommitMailboxDrain<T>> drained = drainAll(policy);
        List<GACommitEngine.GACommitFailure<T>> failures = null;
        int inputCount = 0;
        int acceptedCount = 0;
        int rejectedCount = 0;
        int collisionCount = 0;
        for (GACommitMailboxDrain<T> drain : drained) {
            GACommitBatchStats stats = drain.resolved().stats();
            inputCount += stats.inputCount();
            acceptedCount += stats.acceptedCount();
            rejectedCount += stats.rejectedCount();
            collisionCount += stats.collisionCount();
            for (GACommitCommand<T> command : drain.resolved().accepted()) {
                try {
                    applier.apply(command);
                } catch (Exception exception) {
                    if (failures == null) {
                        failures = new ArrayList<>();
                    }
                    failures.add(new GACommitEngine.GACommitFailure<>(command, exception));
                }
            }
        }
        int failureCount = failures == null ? 0 : failures.size();
        long executionNanos = Math.max(0L, System.nanoTime() - startNanos);
        GACommitMetrics metrics = new GACommitMetrics(
                drained.size(),
                inputCount,
                acceptedCount,
                rejectedCount,
                collisionCount,
                executionNanos,
                failureCount
        );
        GACommitMetrics.record(metrics);
        return new GACommitMailboxExecution<>(drained, metrics, failures);
    }

    public record GACommitMailboxDrain<T>(
            GAChunkPosition targetChunk,
            GACommitBatch.GAResolvedCommitBatch<T> resolved
    ) {
    }

    public record GACommitMailboxExecution<T>(
            List<GACommitMailboxDrain<T>> drained,
            GACommitMetrics metrics,
            List<GACommitEngine.GACommitFailure<T>> failures
    ) {
    }
}
