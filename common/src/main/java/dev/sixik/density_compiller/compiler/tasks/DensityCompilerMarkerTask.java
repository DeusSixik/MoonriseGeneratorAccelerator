package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerMarkerTask extends DensityCompilerTask<DensityFunctions.Marker> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Marker node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.wrapped());
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Marker node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.wrapped(), destArrayVar);
    }
}
