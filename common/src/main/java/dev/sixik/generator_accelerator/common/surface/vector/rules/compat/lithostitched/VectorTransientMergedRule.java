package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorTransientMergedRule implements VectorRule {
    private final VectorRule[] rules;

    public VectorTransientMergedRule(VectorRule[] rules) {
        this.rules = rules;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {
        for (int i = 0; i < this.rules.length; i++) {
            if (activeMask.isEmpty()) {
                break;
            }

            this.rules[i].apply(rawBlockData, activeMask, ctx);
        }
    }

    @Override
    public int requiredContext() {
        int requirements = 0;
        for (VectorRule rule : this.rules) {
            requirements |= rule.requiredContext();
        }
        return requirements;
    }
}
