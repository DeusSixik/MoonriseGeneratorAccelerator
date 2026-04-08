package dev.sixik.generator_accelerator.common.surface.vector;

import java.util.BitSet;

public interface VectorRule {

    void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx);
}
