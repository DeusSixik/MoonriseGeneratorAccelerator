package dev.sixik.generator_accelerator.common.surface.vector;

import java.util.BitSet;

public class VectorSurfaceRules {

    private final VectorRule rootRule;

    public VectorSurfaceRules(VectorRule rootRule) {
        this.rootRule = rootRule;
    }

    public void applyToSection(int[] rawBlockData, BitSet stoneMask, VectorChunkContext ctx) {
        BitSet processingMask = ctx.acquireBitSet4096();
        try {
            processingMask.or(stoneMask);
            this.rootRule.apply(rawBlockData, processingMask, ctx);
        } finally {
            ctx.releaseBitSet4096(processingMask);
        }
    }
}
