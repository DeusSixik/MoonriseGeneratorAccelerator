package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.List;

public record GACommitCollisionResult<T>(
        List<GACommitCommand<T>> accepted,
        List<GACommitCommand<T>> rejected,
        int collisionCount
) {
    public GACommitCollisionResult(List<GACommitCommand<T>> accepted, List<GACommitCommand<T>> rejected) {
        this(accepted, rejected, 0);
    }

    public GACommitCollisionResult {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        if (collisionCount < 0) {
            throw new IllegalArgumentException("collisionCount must be non-negative");
        }
    }

    public boolean hasRejected() {
        return !rejected.isEmpty();
    }
}
