package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorAllOfCondition implements VectorCondition {
    private final VectorCondition[] conditions;

    public VectorAllOfCondition(VectorCondition[] conditions) {
        this.conditions = conditions;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        for (int i = 0; i < conditions.length; i++) {
            if (activeMask.isEmpty()) break;
            conditions[i].filter(activeMask, ctx);
        }
    }

    @Override
    public int requiredContext() {
        int requirements = 0;
        for (VectorCondition condition : this.conditions) {
            requirements |= condition.requiredContext();
        }
        return requirements;
    }
}
