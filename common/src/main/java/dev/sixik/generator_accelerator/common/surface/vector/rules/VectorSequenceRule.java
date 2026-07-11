package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorSequenceRule implements VectorRule {
    private final VectorRule[] rules;

    public VectorSequenceRule(VectorRule[] rules) {
        this.rules = rules;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {

        VectorRule[] vectorRules = this.rules;
        for (int i = 0; i < vectorRules.length; i++) {
            if (activeMask.isEmpty()) {
                break; // All blocks are painted, break the cycle
            }

            vectorRules[i].apply(rawBlockData, activeMask, ctx);
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
