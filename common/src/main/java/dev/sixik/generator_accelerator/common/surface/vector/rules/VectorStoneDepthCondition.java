package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

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
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;

                int xzIdx = localX | (localZ << 4);

                int allowedDepth = 1 + this.offset;

                if (this.addSurfaceDepth) {
                    allowedDepth += ctx.surfaceDepths[xzIdx];
                }

                if (this.secondaryDepthRange != 0) {
                    double secondaryNoise = ctx.secondarySurfaceNoises[xzIdx];
                    allowedDepth += (int) Mth.map(secondaryNoise, -1.0, 1.0, 0.0, this.secondaryDepthRange);
                }

                int currentDepth = this.isCeiling ? ctx.stoneDepthBelow[i] : ctx.stoneDepthAbove[i];

                if (currentDepth == 0 || currentDepth > allowedDepth) {
                    activeMask.clear(i);
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requiredContext() {
        int requirements = 0;
        if (this.addSurfaceDepth) {
            requirements |= VectorContextRequirements.SURFACE_DEPTHS;
        }
        if (this.secondaryDepthRange != 0) {
            requirements |= VectorContextRequirements.SECONDARY_SURFACE_NOISE;
        }
        return requirements;
    }
}
