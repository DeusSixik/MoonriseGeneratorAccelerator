package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import java.util.BitSet;

public class VectorNotCondition implements VectorCondition {
    private final VectorCondition target;

    public VectorNotCondition(VectorCondition target) {
        this.target = target;
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty()) return;

        BitSet passedMask = ctx.acquireBitSet4096();
        try {
            passedMask.or(activeMask);
            this.target.filter(passedMask, ctx);
            activeMask.xor(passedMask);
        } finally {
            ctx.releaseBitSet4096(passedMask);
        }
    }
}
