package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.List;

public record GACommitCollisionResult<T>(
        List<GACommitCommand<T>> accepted,
        List<GACommitCommand<T>> rejected
) {
    public GACommitCollisionResult {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    public boolean hasRejected() {
        return !rejected.isEmpty();
    }
}
