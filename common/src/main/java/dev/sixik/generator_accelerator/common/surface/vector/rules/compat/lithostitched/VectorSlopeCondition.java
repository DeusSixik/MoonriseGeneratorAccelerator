package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import net.minecraft.util.InclusiveRange;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorSlopeCondition implements VectorCondition {
    private final int minDiff;
    private final int maxDiff;

    public VectorSlopeCondition(InclusiveRange<Integer> threshold) {
        this.minDiff = threshold.minInclusive();
        this.maxDiff = threshold.maxInclusive();
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        short[] heights = ctx.surfaceHeights;

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
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
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requiredContext() {
        return VectorContextRequirements.SURFACE_HEIGHTS;
    }
}
