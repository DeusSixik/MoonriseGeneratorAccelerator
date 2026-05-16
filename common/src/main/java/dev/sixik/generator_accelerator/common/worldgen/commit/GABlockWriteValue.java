package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.Objects;

/**
 * Commit payload for a block state write plus its vanilla update flags.
 */
public record GABlockWriteValue(Object state, int flags) {
    public GABlockWriteValue {
        Objects.requireNonNull(state, "state");
    }
}
