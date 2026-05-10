package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.Objects;

public record GABlockMutation(
        int x,
        int y,
        int z,
        Object state,
        int flags,
        long sequence
) {
    public GABlockMutation {
        Objects.requireNonNull(state, "state");
    }
}
