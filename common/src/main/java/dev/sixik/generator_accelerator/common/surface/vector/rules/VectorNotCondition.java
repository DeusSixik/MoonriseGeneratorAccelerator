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

        /*
            We make a copy of the current mask
            (It contains ALL the blocks we're currently testing)
         */
        BitSet passedMask = (BitSet) activeMask.clone();

        /*
             We run the 'copy' through the target condition.
             PassedMask will only contain '1' values for blocks that PASS the test.
         */
        this.target.filter(passedMask, ctx);

        /*
            We invert the results using XOR.
            activeMask = [1, 1, 1] (All blocks tested)
            passedMask = [0, 1, 0] (Only the second block passed)
            activeMask ^ passedMask = [1, 0, 1] (The first and third blocks remain)
         */
        activeMask.xor(passedMask);
    }
}
