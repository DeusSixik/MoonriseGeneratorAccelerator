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
        // We make a copy of the mask for testing (so as not to spoil the original)
        BitSet matchingMask = (BitSet) activeMask.clone();

        // We filter out unnecessary data (for example, we remove bits where the height is < 60)
        this.condition.filter(matchingMask, ctx);

        if (matchingMask.isEmpty())
            return; // Not a single block passed the test

        BitSet processedBlocks = (BitSet) matchingMask.clone();

        // We apply the following rule (it will fill the blocks and clear the matchingMask)
        this.thenRun.apply(rawBlockData, matchingMask, ctx);

        // We remove from the global mask those blocks that we actually painted over
        processedBlocks.xor(matchingMask);
        activeMask.andNot(processedBlocks);
    }
}