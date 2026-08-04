package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

/**
 * Owner-local 16x16 chunk column coordinate used by lifecycle handoff plans.
 */
public record GAColumnPosition(int localX, int localZ) implements Comparable<GAColumnPosition> {
    public int packedIndex() {
        return (localZ << 4) | localX;
    }

    public static GAColumnPosition fromPackedIndex(int packedIndex) {
        if (packedIndex < 0 || packedIndex > 255) {
            throw new IllegalArgumentException("packedIndex must be in [0, 255]");
        }
        return new GAColumnPosition(packedIndex & 15, packedIndex >>> 4);
    }

    @Override
    public int compareTo(GAColumnPosition other) {
        return Integer.compare(packedIndex(), other.packedIndex());
    }
}
