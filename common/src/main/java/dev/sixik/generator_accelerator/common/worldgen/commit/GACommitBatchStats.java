package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Deterministic summary for a resolved commit batch.
 */
public record GACommitBatchStats(
        int inputCount,
        int acceptedCount,
        int rejectedCount,
        int collisionCount
) {
    public GACommitBatchStats {
        if (inputCount < 0) {
            throw new IllegalArgumentException("inputCount must be non-negative");
        }
        if (acceptedCount < 0) {
            throw new IllegalArgumentException("acceptedCount must be non-negative");
        }
        if (rejectedCount < 0) {
            throw new IllegalArgumentException("rejectedCount must be non-negative");
        }
        if (collisionCount < 0) {
            throw new IllegalArgumentException("collisionCount must be non-negative");
        }
        if (acceptedCount + rejectedCount != inputCount) {
            throw new IllegalArgumentException("acceptedCount + rejectedCount must equal inputCount");
        }
        if (collisionCount > inputCount) {
            throw new IllegalArgumentException("collisionCount must not exceed inputCount");
        }
    }

    public static GACommitBatchStats empty() {
        return new GACommitBatchStats(0, 0, 0, 0);
    }
}
