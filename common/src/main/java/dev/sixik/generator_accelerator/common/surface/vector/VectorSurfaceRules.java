package dev.sixik.generator_accelerator.common.surface.vector;

import java.util.BitSet;

public class VectorSurfaceRules {

    private final VectorRule rootRule;

    public VectorSurfaceRules(VectorRule rootRule) {
        this.rootRule = rootRule;
    }

    public void applyToSection(int[] rawBlockData, BitSet stoneMask, VectorChunkContext ctx) {
        BitSet processingMask = (BitSet) stoneMask.clone();
        this.rootRule.apply(rawBlockData, processingMask, ctx);
    }
}