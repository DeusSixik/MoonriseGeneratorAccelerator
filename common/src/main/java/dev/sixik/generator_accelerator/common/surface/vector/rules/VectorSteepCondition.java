package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import java.util.BitSet;

public class VectorSteepCondition implements VectorCondition {
    public static final VectorSteepCondition INSTANCE = new VectorSteepCondition();

    private VectorSteepCondition() {}

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;

            int k = Math.max(localZ - 1, 0);
            int l = Math.min(localZ + 1, 15);

            // Reading heights directly from the cache
            int height1 = ctx.surfaceHeights[localX | (k << 4)];
            int height2 = ctx.surfaceHeights[localX | (l << 4)];

            if (height2 >= height1 + 4) continue;

            int k1 = Math.max(localX - 1, 0);
            int l1 = Math.min(localX + 1, 15);
            int height3 = ctx.surfaceHeights[k1 | (localZ << 4)];
            int height4 = ctx.surfaceHeights[l1 | (localZ << 4)];

            if (height3 >= height4 + 4) continue;

            // The slope is not steep
            activeMask.clear(i);
        }
    }
}
