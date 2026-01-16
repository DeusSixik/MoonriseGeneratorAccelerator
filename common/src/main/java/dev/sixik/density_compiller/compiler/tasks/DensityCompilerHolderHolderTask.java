package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerHolderHolderTask extends DensityCompilerTask<DensityFunctions.HolderHolder> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.HolderHolder node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.function().value().getClass());
        ctx.visitNodeCompute(node.function().value(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.HolderHolder node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.function().value().getClass());
        ctx.visitNodeCompute(node.function().value(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.HolderHolder node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.function().value().getClass());
        ctx.visitNodeCompute(node.function().value());
        machine.popStack();
    }
}
