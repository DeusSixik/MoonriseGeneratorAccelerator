package dev.sixik.generator_accelerator.common.surface.vector;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorSurfaceRules {

    private final VectorRule rootRule;

    public VectorSurfaceRules(VectorRule rootRule) {
        this.rootRule = rootRule;
    }

    public void applyToSection(int[] rawBlockData, Mask4096 stoneMask, VectorChunkContext ctx) {
        Mask4096 processingMask = ctx.acquireMask4096();
        try {
            processingMask.copyFrom(stoneMask);
            this.rootRule.apply(rawBlockData, processingMask, ctx);
        } finally {
            ctx.releaseMask4096(processingMask);
        }
    }
}
