package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;

public class DensityComputeGenerator implements DensityCompilerPipelineGenerator{
    @Override
    public void applyMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {

    }

    @Override
    public ByteCodeGeneratorStructure getStructure(DensityCompilerPipeline pipeline) {
        return new ByteCodeGeneratorStructure(0, 0);
    }
}
