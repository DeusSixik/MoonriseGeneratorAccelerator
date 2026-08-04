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
    public static GACommitBatchStats empty() {
        return new GACommitBatchStats(0, 0, 0, 0);
    }
}
