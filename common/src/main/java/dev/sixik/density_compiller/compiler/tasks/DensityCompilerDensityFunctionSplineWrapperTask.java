package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.DensityFunctionSplineWrapper;
import org.objectweb.asm.MethodVisitor;

@Deprecated
public class DensityCompilerDensityFunctionSplineWrapperTask extends DensityCompilerTask<DensityFunctionSplineWrapper> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctionSplineWrapper node, PipelineAsmContext ctx) {
        ctx.visitLeafCall(node);
    }
}
