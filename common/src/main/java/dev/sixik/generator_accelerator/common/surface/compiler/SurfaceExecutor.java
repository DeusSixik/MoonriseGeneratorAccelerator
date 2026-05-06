package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;

public final class SurfaceExecutor {
    private SurfaceExecutor() {
    }

    public static void apply(int[] rawBlockData, Mask4096 stoneMask, VectorChunkContext ctx, SurfaceProgram program, SurfaceScratch scratch) {
        program.apply(rawBlockData, stoneMask, ctx, scratch);
    }
}
