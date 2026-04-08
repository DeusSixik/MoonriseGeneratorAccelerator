package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.world.level.levelgen.VerticalAnchor;

import java.util.BitSet;

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
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty()) return;

        int resolvedAnchorY = this.anchor.resolveY(ctx.worldContext);

        final var surfaceDepthsRef = ctx.surfaceDepths;
        final var stoneDepthAboveRef = ctx.stoneDepthAbove;

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
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
        }
    }
}
