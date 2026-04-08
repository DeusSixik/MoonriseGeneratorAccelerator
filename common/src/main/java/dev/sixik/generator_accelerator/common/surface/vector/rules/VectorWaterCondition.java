package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import java.util.BitSet;

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
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int xzIdx = localX | (localZ << 4);

            // water level for the current column
            int waterHeight = ctx.waterHeights[xzIdx];

            // If there is no liquid above us (Integer.MIN_VALUE), the condition is fulfilled automatically
            if (waterHeight == Integer.MIN_VALUE) {
                continue;
            }

            int localY = (i >> 8) & 15;
            int globalY = ctx.sectionStartY + localY;

            // (LHS)
            int lhs = globalY;
            if (this.addStoneDepth) {
                lhs += ctx.stoneDepthAbove[i];
            }

            // (RHS)
            int rhs = waterHeight + this.offset + (ctx.surfaceDepths[xzIdx] * this.surfaceDepthMultiplier);

            // Clear the bit if the condition is not met
            if (lhs < rhs) {
                activeMask.clear(i);
            }
        }
    }
}
