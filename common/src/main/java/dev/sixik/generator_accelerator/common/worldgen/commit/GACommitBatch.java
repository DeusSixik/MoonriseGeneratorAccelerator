package dev.sixik.generator_accelerator.common.worldgen.commit;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Immutable command collection for later world commit execution.
 */
public final class GACommitBatch<T> {
    private final List<GACommitCommand<T>> commands;

    private GACommitBatch(Collection<GACommitCommand<T>> commands) {
        if (commands == null) {
            throw new NullPointerException("commands");
        }
        this.commands = List.copyOf(commands);
    }

    public static <T> GACommitBatch<T> empty() {
        return new GACommitBatch<>(List.of());
    }

    public static <T> GACommitBatch<T> of(Collection<GACommitCommand<T>> commands) {
        return new GACommitBatch<>(commands);
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public List<GACommitCommand<T>> commands() {
        return commands;
    }

    public GAResolvedCommitBatch<T> resolve(GACommitCollisionPolicy policy) {
        GACommitCollisionResult<T> result = GACommitCollisionResolver.resolve(commands, policy);
        GACommitBatchStats stats = new GACommitBatchStats(
                commands.size(),
                result.accepted().size(),
                result.rejected().size(),
                result.collisionCount()
        );
        return new GAResolvedCommitBatch<>(result.accepted(), result.rejected(), stats);
    }

    public static final class Builder<T> {
        private final List<GACommitCommand<T>> commands = new ObjectArrayList<>();

        public Builder<T> add(GACommitCommand<T> command) {
            if (command == null) {
                throw new NullPointerException("command");
            }
            commands.add(command);
            return this;
        }

        public Builder<T> addAll(Collection<GACommitCommand<T>> commands) {
            if (commands == null) {
                throw new NullPointerException("commands");
            }
            for (GACommitCommand<T> command : commands) {
                add(command);
            }
            return this;
        }

        public GACommitBatch<T> build() {
            return new GACommitBatch<>(commands);
        }
    }

    public record GAResolvedCommitBatch<T>(
            List<GACommitCommand<T>> accepted,
            List<GACommitCommand<T>> rejected,
            GACommitBatchStats stats
    ) {
        public GAResolvedCommitBatch {
            accepted = accepted == null ? List.of() : List.copyOf(accepted);
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
            if (stats == null) {
                throw new NullPointerException("stats");
            }
        }

        public boolean hasRejected() {
            return !rejected.isEmpty();
        }
    }
}
