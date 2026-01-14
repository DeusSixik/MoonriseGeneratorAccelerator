package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerNoiseInterpolatorTask extends DensityCompilerTask<NoiseChunk.NoiseInterpolator> {
    @Override
    protected void compileCompute(MethodVisitor mv, NoiseChunk.NoiseInterpolator node, PipelineAsmContext ctx) {
        ctx.visitLeafCall(node);
    }
}
