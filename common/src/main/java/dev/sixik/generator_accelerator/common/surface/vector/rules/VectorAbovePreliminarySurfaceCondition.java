package dev.sixik.generator_accelerator.common.surface.vector.rules;


import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorAbovePreliminarySurfaceCondition implements VectorCondition {
    public static final VectorAbovePreliminarySurfaceCondition INSTANCE = new VectorAbovePreliminarySurfaceCondition();

    private VectorAbovePreliminarySurfaceCondition() {}

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty()) return;

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int localY = (i >> 8) & 15;

                int globalY = ctx.sectionStartY + localY;

                int minLevel = ctx.minSurfaceLevels[localX | (localZ << 4)];

                if (globalY < minLevel) {
                    activeMask.clear(i);
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requiredContext() {
        return VectorContextRequirements.SURFACE_DEPTHS | VectorContextRequirements.PRELIMINARY_SURFACE;
    }
}
