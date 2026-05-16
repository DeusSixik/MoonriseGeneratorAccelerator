package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.Objects;

/**
 * Value-only payload for future owner-applied heightmap repair/update work.
 */
public record GAHeightmapUpdateValue(
        String heightmapId,
        int height,
        boolean dirtyOnly
) {
    public GAHeightmapUpdateValue {
        Objects.requireNonNull(heightmapId, "heightmapId");
        if (heightmapId.isBlank()) {
            throw new IllegalArgumentException("heightmapId must not be blank");
        }
    }
}
