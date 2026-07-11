package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import net.minecraft.world.level.levelgen.VerticalAnchor;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorYCondition implements VectorCondition {
    private final VerticalAnchor anchor; // Храним ванильный объект
    private final int surfaceDepthMultiplier;
    private final boolean addStoneDepth;

    public VectorYCondition(VerticalAnchor anchor, int surfaceDepthMultiplier, boolean addStoneDepth) {
        this.anchor = anchor;
        this.surfaceDepthMultiplier = surfaceDepthMultiplier;
        this.addStoneDepth = addStoneDepth;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty()) return;

        int resolvedAnchorY = this.anchor.resolveY(ctx.worldContext);

        final var surfaceDepthsRef = ctx.surfaceDepths;
        final var stoneDepthAboveRef = ctx.stoneDepthAbove;

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int localY = (i >> 8) & 15;

                int xzIdx = localX | (localZ << 4);
                int globalY = ctx.sectionStartY + localY;

                int lhs = globalY;
                if (this.addStoneDepth) {
                    lhs += stoneDepthAboveRef[i];
                }

                int rhs = resolvedAnchorY + (surfaceDepthsRef[xzIdx] * this.surfaceDepthMultiplier);

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
