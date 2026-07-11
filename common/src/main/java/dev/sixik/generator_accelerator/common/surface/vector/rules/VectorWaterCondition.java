package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorWaterCondition implements VectorCondition {
    private final int offset;
    private final int surfaceDepthMultiplier;
    private final boolean addStoneDepth;

    public VectorWaterCondition(int offset, int surfaceDepthMultiplier, boolean addStoneDepth) {
        this.offset = offset;
        this.surfaceDepthMultiplier = surfaceDepthMultiplier;
        this.addStoneDepth = addStoneDepth;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int xzIdx = localX | (localZ << 4);

                int waterHeight = ctx.waterHeights[xzIdx];

                if (waterHeight == Integer.MIN_VALUE) {
                    word &= word - 1L;
                    continue;
                }

                int localY = (i >> 8) & 15;
                int globalY = ctx.sectionStartY + localY;

                int lhs = globalY;
                if (this.addStoneDepth) {
                    lhs += ctx.stoneDepthAbove[i];
                }

                int rhs = waterHeight + this.offset + (ctx.surfaceDepths[xzIdx] * this.surfaceDepthMultiplier);

                if (lhs < rhs) {
                    activeMask.clear(i);
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requiredContext() {
        return this.surfaceDepthMultiplier == 0 ? 0 : VectorContextRequirements.SURFACE_DEPTHS;
    }
}
