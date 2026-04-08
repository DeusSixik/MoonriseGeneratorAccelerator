package dev.sixik.generator_accelerator.common.surface.vector;

import java.util.BitSet;

public interface VectorCondition {

    void filter(BitSet activeMask, VectorChunkContext ctx);

}
