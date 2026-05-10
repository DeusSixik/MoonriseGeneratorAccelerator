package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.Comparator;

/**
 * Stable logical order for future workspace/journal commit commands.
 */
public record GACommitOrderKey(
        int phase,
        int step,
        int chunkX,
        int chunkZ,
        int unitX,
        int unitZ,
        int localIndex,
        long sequence
) implements Comparable<GACommitOrderKey> {
    public static final Comparator<GACommitOrderKey> COMPARATOR = Comparator
            .comparingInt(GACommitOrderKey::phase)
            .thenComparingInt(GACommitOrderKey::step)
            .thenComparingInt(GACommitOrderKey::chunkX)
            .thenComparingInt(GACommitOrderKey::chunkZ)
            .thenComparingInt(GACommitOrderKey::unitX)
            .thenComparingInt(GACommitOrderKey::unitZ)
            .thenComparingInt(GACommitOrderKey::localIndex)
            .thenComparingLong(GACommitOrderKey::sequence);

    public static GACommitOrderKey chunkLocal(
            int phase,
            int step,
            int chunkX,
            int chunkZ,
            int localIndex,
            long sequence
    ) {
        return new GACommitOrderKey(phase, step, chunkX, chunkZ, chunkX, chunkZ, localIndex, sequence);
    }

    @Override
    public int compareTo(GACommitOrderKey other) {
        return COMPARATOR.compare(this, other);
    }
}
