package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

import java.util.BitSet;

public class VectorStoneDepthCondition implements VectorCondition {
    private final int offset;
    private final boolean addSurfaceDepth;
    private final int secondaryDepthRange;
    private final boolean isCeiling;

    public VectorStoneDepthCondition(int offset, boolean addSurfaceDepth, int secondaryDepthRange, CaveSurface surfaceType) {
        this.offset = offset;
        this.addSurfaceDepth = addSurfaceDepth;
        this.secondaryDepthRange = secondaryDepthRange;
        this.isCeiling = (surfaceType == CaveSurface.CEILING);
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        // We only run through the active blocks in the mask
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;

            // index for 2D array (X and Z)
            int xzIdx = localX | (localZ << 4);

            // maximum permitted depth
            int allowedDepth = 1 + this.offset;

            if (this.addSurfaceDepth) {
                allowedDepth += ctx.surfaceDepths[xzIdx];
            }

            if (this.secondaryDepthRange != 0) {
                double secondaryNoise = ctx.secondarySurfaceNoises[xzIdx];
                allowedDepth += (int) Mth.map(secondaryNoise, -1.0, 1.0, 0.0, this.secondaryDepthRange);
            }

            // current depth of the block in the rock
            int currentDepth = this.isCeiling ? ctx.stoneDepthBelow[i] : ctx.stoneDepthAbove[i];

            /*
                If we're deeper than allowed (or even in midair, where depth = 0),
                then this block has failed the test. We remove the bit.
             */
            if (currentDepth == 0 || currentDepth > allowedDepth) {
                activeMask.clear(i);
            }
        }
    }
}
