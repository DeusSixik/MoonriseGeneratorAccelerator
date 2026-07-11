package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorSteepCondition implements VectorCondition {
    public static final VectorSteepCondition INSTANCE = new VectorSteepCondition();

    private VectorSteepCondition() {}

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;

                int k = Math.max(localZ - 1, 0);
                int l = Math.min(localZ + 1, 15);

                int height1 = ctx.surfaceHeights[localX | (k << 4)];
                int height2 = ctx.surfaceHeights[localX | (l << 4)];

                if (height2 >= height1 + 4) {
                    word &= word - 1L;
                    continue;
                }

                int k1 = Math.max(localX - 1, 0);
                int l1 = Math.min(localX + 1, 15);
                int height3 = ctx.surfaceHeights[k1 | (localZ << 4)];
                int height4 = ctx.surfaceHeights[l1 | (localZ << 4)];

                if (height3 >= height4 + 4) {
                    word &= word - 1L;
                    continue;
                }

                activeMask.clear(i);
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requiredContext() {
        return VectorContextRequirements.SURFACE_HEIGHTS;
    }
}
