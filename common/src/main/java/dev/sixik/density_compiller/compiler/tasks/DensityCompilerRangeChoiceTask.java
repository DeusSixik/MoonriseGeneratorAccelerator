package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerRangeChoiceTask extends DensityCompilerTask<DensityFunctions.RangeChoice> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenInRange(), PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenOutOfRange(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenInRange(), POST_PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenOutOfRange(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        double min = node.minInclusive();
        double max = node.maxExclusive();
        double inMin = node.input().minValue();
        double inMax = node.input().maxValue();

        if (inMin >= min && inMax < max) {
            machine.pushStack(node.getClass(), node.whenInRange().getClass());
            ctx.visitNodeCompute(node.whenInRange());
            machine.popStack();
            return;
        }
        if (inMax < min || inMin >= max) {
            machine.pushStack(node.getClass(), node.whenOutOfRange().getClass());
            ctx.visitNodeCompute(node.whenOutOfRange());
            machine.popStack();
            return;
        }

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input());
        machine.popStack();
        int d = ctx.createDoubleVarFromStack();

        ctx.readDoubleVar(d);
        ctx.mv().visitLdcInsn(min);
        ctx.ifElse(ctx.doubleGe(), () -> {

            ctx.readDoubleVar(d);
            ctx.mv().visitLdcInsn(max);
            ctx.ifThen(ctx.doubleLe(), () -> {
                machine.pushStack(node.getClass(), node.whenInRange().getClass());
                ctx.visitNodeCompute(node.whenInRange());
                machine.popStack();
                ctx.writeDoubleVar(d);
            });
        }, () -> {
            machine.pushStack(node.getClass(), node.whenOutOfRange().getClass());
            ctx.visitNodeCompute(node.whenOutOfRange());
            machine.popStack();
            ctx.writeDoubleVar(d);
        });

        ctx.readDoubleVar(d);
    }
}