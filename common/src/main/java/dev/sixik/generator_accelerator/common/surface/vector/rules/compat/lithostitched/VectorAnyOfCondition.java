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

        BitSet finalPassedMask = new BitSet(256);

        for (int i = 0; i < conditions.length; i++) {
            BitSet testMask = (BitSet) activeMask.clone();
            testMask.andNot(finalPassedMask);

            if (testMask.isEmpty()) break;

            conditions[i].filter(testMask, ctx);

            finalPassedMask.or(testMask);
        }

        activeMask.and(finalPassedMask);
    }
}
