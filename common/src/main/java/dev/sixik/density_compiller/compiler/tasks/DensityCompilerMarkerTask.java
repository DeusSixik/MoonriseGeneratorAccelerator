package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerMarkerTask extends DensityCompilerTask<DensityFunctions.Marker> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.Marker node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.wrapped().getClass());
        ctx.visitNodeCompute(node.wrapped(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.Marker node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.wrapped().getClass());
        ctx.visitNodeCompute(node.wrapped(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Marker node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.wrapped().getClass());
        ctx.visitNodeCompute(node.wrapped());
        machine.popStack();
    }
}
