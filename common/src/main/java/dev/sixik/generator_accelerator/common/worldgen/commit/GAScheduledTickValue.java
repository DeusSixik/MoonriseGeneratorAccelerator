package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.Objects;

/**
 * Value-only payload for future owner-applied block/fluid scheduled tick side effects.
 */
public record GAScheduledTickValue(
        GAScheduledTickType type,
        Object target,
        int delay,
        int priority,
        long sourceSequence
) {
    public enum GAScheduledTickType {
        BLOCK,
        FLUID
    }
}
