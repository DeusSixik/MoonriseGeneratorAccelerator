package dev.sixik.density_compiller.compiler.pipeline;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;

public class DensityMaxValueGenerator implements DensityCompilerPipelineGenerator {
    @Override
    public void apply(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {

    }
}
