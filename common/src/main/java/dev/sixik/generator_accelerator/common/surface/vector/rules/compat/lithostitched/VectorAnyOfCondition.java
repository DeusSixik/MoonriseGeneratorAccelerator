package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import java.util.BitSet;

public class VectorAnyOfCondition implements VectorCondition {
    private final VectorCondition[] conditions;

    public VectorAnyOfCondition(VectorCondition[] conditions) {
        this.conditions = conditions;
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty() || conditions.length == 0) {
            activeMask.clear();
            return;
        }

        BitSet finalPassedMask = ctx.acquireBitSet4096();
        BitSet testMask = ctx.acquireBitSet4096();
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
            ctx.releaseBitSet4096(testMask);
            ctx.releaseBitSet4096(finalPassedMask);
        }
    }
}
