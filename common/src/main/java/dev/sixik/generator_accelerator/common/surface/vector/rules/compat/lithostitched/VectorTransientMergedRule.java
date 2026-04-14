package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;

import java.util.BitSet;

public class VectorTransientMergedRule implements VectorRule {
    private final VectorRule[] rules;

    public VectorTransientMergedRule(VectorRule[] rules) {
        this.rules = rules;
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        for (int i = 0; i < this.rules.length; i++) {
            if (activeMask.isEmpty()) {
                break;
            }

            this.rules[i].apply(rawBlockData, activeMask, ctx);
        }
    }
}
