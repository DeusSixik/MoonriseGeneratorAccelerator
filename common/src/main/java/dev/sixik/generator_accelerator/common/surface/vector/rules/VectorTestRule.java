package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorTestRule implements VectorRule {
    private final VectorCondition condition;
    private final VectorRule thenRun;

    public VectorTestRule(VectorCondition condition, VectorRule thenRun) {
        this.condition = condition;
        this.thenRun = thenRun;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {
        Mask4096 matchingMask = ctx.acquireMask4096();
        try {
            matchingMask.or(activeMask);
            this.condition.filter(matchingMask, ctx);

            if (matchingMask.isEmpty()) {
                return;
            }

            Mask4096 processedBlocks = ctx.acquireMask4096();
            try {
                processedBlocks.or(matchingMask);
                this.thenRun.apply(rawBlockData, matchingMask, ctx);
                processedBlocks.xor(matchingMask);
                activeMask.andNot(processedBlocks);
            } finally {
                ctx.releaseMask4096(processedBlocks);
            }
        } finally {
            ctx.releaseMask4096(matchingMask);
        }
    }

    @Override
    public int requiredContext() {
        return this.condition.requiredContext() | this.thenRun.requiredContext();
    }
}
