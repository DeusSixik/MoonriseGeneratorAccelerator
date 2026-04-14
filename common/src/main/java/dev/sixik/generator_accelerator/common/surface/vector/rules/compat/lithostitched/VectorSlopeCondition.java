package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.util.InclusiveRange;

import java.util.BitSet;

public class VectorSlopeCondition implements VectorCondition {
    private final int minDiff;
    private final int maxDiff;

    public VectorSlopeCondition(InclusiveRange<Integer> threshold) {
        this.minDiff = threshold.minInclusive();
        this.maxDiff = threshold.maxInclusive();
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        short[] heights = ctx.surfaceHeights;

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int x = i & 15;
            int z = (i >> 4) & 15;

            int north = Math.max(z - 1, 0);
            int south = Math.min(z + 1, 15);
            int west = Math.max(x - 1, 0);
            int east = Math.min(x + 1, 15);

            int hNorth = heights[x | (north << 4)];
            int hSouth = heights[x | (south << 4)];
            int hWest  = heights[west | (z << 4)];
            int hEast  = heights[east | (z << 4)];

            int maxH = hNorth;
            if (hSouth > maxH) maxH = hSouth;
            if (hWest > maxH) maxH = hWest;
            if (hEast > maxH) maxH = hEast;

            int minH = hNorth;
            if (hSouth < minH) minH = hSouth;
            if (hWest < minH) minH = hWest;
            if (hEast < minH) minH = hEast;

            int diff = maxH - minH;

            if (diff < minDiff || diff > maxDiff) {
                activeMask.clear(i);
            }
        }
    }
}
