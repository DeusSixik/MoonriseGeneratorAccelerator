package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Immutable chunk coordinate for detached commit routing.
 */
public record GAChunkPosition(int x, int z) implements Comparable<GAChunkPosition> {
    public static GAChunkPosition fromBlock(GABlockPosition position) {
        if (position == null) {
            throw new NullPointerException("position");
        }
        return new GAChunkPosition(Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16));
    }

    @Override
    public int compareTo(GAChunkPosition other) {
        int byX = Integer.compare(x, other.x);
        if (byX != 0) {
            return byX;
        }
        return Integer.compare(z, other.z);
    }
}
