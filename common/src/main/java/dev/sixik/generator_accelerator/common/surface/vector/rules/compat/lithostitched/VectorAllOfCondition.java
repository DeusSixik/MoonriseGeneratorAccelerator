package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import java.util.BitSet;

public class VectorAllOfCondition implements VectorCondition {
    private final VectorCondition[] conditions;

    public VectorAllOfCondition(VectorCondition[] conditions) {
        this.conditions = conditions;
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        for (int i = 0; i < conditions.length; i++) {
            if (activeMask.isEmpty()) break;
            conditions[i].filter(activeMask, ctx);
        }
    }
}
