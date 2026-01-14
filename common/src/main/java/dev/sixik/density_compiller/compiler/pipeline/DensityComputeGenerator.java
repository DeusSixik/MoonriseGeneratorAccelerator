package dev.sixik.density_compiller.compiler.pipeline;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import net.minecraft.world.level.levelgen.DensityFunction;

public class DensityComputeGenerator implements DensityCompilerPipelineGenerator{
    @Override
    public void apply(DensityCompilerPipeline pipeline, AsmCtx ctx, DensityFunction root, String className, String classSimpleName, int id) {

    }

    @Override
    public ByteCodeGeneratorStructure getStructure() {
        return new ByteCodeGeneratorStructure(0, 0);
    }
}
