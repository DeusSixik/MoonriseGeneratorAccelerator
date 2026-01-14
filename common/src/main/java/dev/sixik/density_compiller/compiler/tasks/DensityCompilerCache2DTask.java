package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerCache2DTask extends DensityCompilerTask<NoiseChunk.Cache2D> {
    @Override
    protected void compileCompute(MethodVisitor mv, NoiseChunk.Cache2D node, PipelineAsmContext ctx) {
        ctx.visitLeafCall(node);
    }

//    @Override
//    public void compileFill(MethodVisitor mv, NoiseChunk.Cache2D node, PipelineAsmContext ctx, int destArrayVar) {
//        ctx.visitNodeFill(node.wrapped(), destArrayVar);
//    }
}
