package dev.sixik.generator_accelerator.common.surface.vector;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public interface VectorRule {

    void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx);

    default int requiredContext() {
        return 0;
    }
}
