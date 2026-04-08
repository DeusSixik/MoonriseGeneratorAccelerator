package dev.sixik.generator_accelerator.common.surface.vector.rules;


import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import java.util.BitSet;

public class VectorAbovePreliminarySurfaceCondition implements VectorCondition {
    public static final VectorAbovePreliminarySurfaceCondition INSTANCE = new VectorAbovePreliminarySurfaceCondition();

    private VectorAbovePreliminarySurfaceCondition() {}

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty()) return;

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int localY = (i >> 8) & 15;

            // global Y of our block
            int globalY = ctx.sectionStartY + localY;

            // We take the threshold from our cache (interpolated noise)
            int minLevel = ctx.minSurfaceLevels[localX | (localZ << 4)];

            /*
                Vanilla requires: blockY >= minLevel.
                So, if we're BELOW that level, we fail the test.
             */
            if (globalY < minLevel) {
                activeMask.clear(i);
            }
        }
    }
}