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
}
