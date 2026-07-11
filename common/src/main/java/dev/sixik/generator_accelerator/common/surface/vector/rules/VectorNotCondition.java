package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorNotCondition implements VectorCondition {
    private final VectorCondition target;

    public VectorNotCondition(VectorCondition target) {
        this.target = target;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        if (activeMask.isEmpty()) return;

        Mask4096 passedMask = ctx.acquireMask4096();
        try {
            passedMask.or(activeMask);
            this.target.filter(passedMask, ctx);
            activeMask.xor(passedMask);
        } finally {
            ctx.releaseMask4096(passedMask);
        }
    }

    @Override
    public int requiredContext() {
        return this.target.requiredContext();
    }
}
