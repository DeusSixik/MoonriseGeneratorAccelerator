package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Minimal immutable command shell for future commit engines.
 */
public record GACommitCommand<T>(
        GABlockPosition position,
        GACommitOrderKey orderKey,
        T value
) implements Comparable<GACommitCommand<T>> {
    public GACommitCommand {
        if (position == null) {
            throw new NullPointerException("position");
        }
        if (orderKey == null) {
            throw new NullPointerException("orderKey");
        }
    }

    @Override
    public int compareTo(GACommitCommand<T> other) {
        int byPosition = position.compareTo(other.position);
        if (byPosition != 0) {
            return byPosition;
        }
        return orderKey.compareTo(other.orderKey);
    }
}
