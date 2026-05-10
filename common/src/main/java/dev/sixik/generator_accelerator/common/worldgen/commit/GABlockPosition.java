package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Immutable block target for commit collision grouping.
 */
public record GABlockPosition(int x, int y, int z) implements Comparable<GABlockPosition> {
    @Override
    public int compareTo(GABlockPosition other) {
        int byX = Integer.compare(x, other.x);
        if (byX != 0) {
            return byX;
        }
        int byY = Integer.compare(y, other.y);
        if (byY != 0) {
            return byY;
        }
        return Integer.compare(z, other.z);
    }
}
