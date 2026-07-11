package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorAnyOfCondition implements VectorCondition {
    private final VectorCondition[] conditions;

    public VectorAnyOfCondition(VectorCondition[] conditions) {
        this.conditions = conditions;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty() || conditions.length == 0) {
            activeMask.clear();
            return;
        }

        Mask4096 finalPassedMask = ctx.acquireMask4096();
        Mask4096 testMask = ctx.acquireMask4096();
        try {
            for (int i = 0; i < conditions.length; i++) {
                testMask.clear();
                testMask.or(activeMask);
                testMask.andNot(finalPassedMask);

                if (testMask.isEmpty()) break;

                conditions[i].filter(testMask, ctx);

                finalPassedMask.or(testMask);
            }

            activeMask.and(finalPassedMask);
        } finally {
            ctx.releaseMask4096(testMask);
            ctx.releaseMask4096(finalPassedMask);
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
