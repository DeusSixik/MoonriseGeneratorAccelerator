package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerBeardifierMarkerTask extends DensityCompilerTask<DensityFunctions.BeardifierMarker> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.BeardifierMarker node, PipelineAsmContext ctx) {
        mv.visitLdcInsn(0.0);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.BeardifierMarker node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.arrayForFill(destArrayVar, 0.0);
    }
}
