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

    public boolean hasRejected() {
        return !rejected.isEmpty();
    }
}
