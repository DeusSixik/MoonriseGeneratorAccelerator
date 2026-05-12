package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.Objects;

/**
 * Detached placeholder payload for block/fluid scheduled tick side effects.
 */
public record GAScheduledTickValue(
        GAScheduledTickType type,
        Object target,
        int delay,
        int priority,
        long sourceSequence
) {
    public GAScheduledTickValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be non-negative");
        }
        if (sourceSequence < 0L) {
            throw new IllegalArgumentException("sourceSequence must be non-negative");
        }
    }

    public enum GAScheduledTickType {
        BLOCK,
        FLUID
    }
}
