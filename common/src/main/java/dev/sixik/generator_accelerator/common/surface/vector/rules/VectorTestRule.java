package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;

import java.util.BitSet;

public class VectorTestRule implements VectorRule {
    private final VectorCondition condition;
    private final VectorRule thenRun;

    public VectorTestRule(VectorCondition condition, VectorRule thenRun) {
        this.condition = condition;
        this.thenRun = thenRun;
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        BitSet matchingMask = ctx.acquireBitSet4096();
        try {
            matchingMask.or(activeMask);
            this.condition.filter(matchingMask, ctx);

            if (matchingMask.isEmpty()) {
                return;
            }

            BitSet processedBlocks = ctx.acquireBitSet4096();
            try {
                processedBlocks.or(matchingMask);
                this.thenRun.apply(rawBlockData, matchingMask, ctx);
                processedBlocks.xor(matchingMask);
                activeMask.andNot(processedBlocks);
            } finally {
                ctx.releaseBitSet4096(processedBlocks);
            }
        } finally {
            ctx.releaseBitSet4096(matchingMask);
        }
    }
}
