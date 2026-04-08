package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import java.util.BitSet;

public class VectorHoleCondition implements VectorCondition {
    public static final VectorHoleCondition INSTANCE = new VectorHoleCondition();

    private VectorHoleCondition() {}

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;

            // If the surface depth is greater than 0, it is not a "hole"
            if (ctx.surfaceDepths[localX | (localZ << 4)] > 0) {
                activeMask.clear(i);
            }
        }
    }
}
