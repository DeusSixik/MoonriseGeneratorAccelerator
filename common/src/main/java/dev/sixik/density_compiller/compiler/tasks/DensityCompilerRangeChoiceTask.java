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
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        ctx.visitNodeCompute(node.whenInRange(), PREPARE_COMPUTE);
        ctx.visitNodeCompute(node.whenOutOfRange(), PREPARE_COMPUTE);
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        ctx.visitNodeCompute(node.whenInRange(), POST_PREPARE_COMPUTE);
        ctx.visitNodeCompute(node.whenOutOfRange(), POST_PREPARE_COMPUTE);
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        double min = node.minInclusive();
        double max = node.maxExclusive();
        double inMin = node.input().minValue();
        double inMax = node.input().maxValue();

        if (inMin >= min && inMax < max) { ctx.visitNodeCompute(node.whenInRange()); return; }
        if (inMax < min || inMin >= max) { ctx.visitNodeCompute(node.whenOutOfRange()); return; }

        ctx.visitNodeCompute(node.input());
        int d = ctx.createDoubleVarFromStack();

        ctx.readDoubleVar(d);
        ctx.mv().visitLdcInsn(min);
        ctx.ifElse(ctx.doubleGe(), () -> {

            ctx.readDoubleVar(d);
            ctx.mv().visitLdcInsn(max);
            ctx.ifThen(ctx.doubleLe(), () -> {
                ctx.visitNodeCompute(node.whenInRange());
                ctx.writeDoubleVar(d);
            });
        }, () -> {
            ctx.visitNodeCompute(node.whenOutOfRange());
            ctx.writeDoubleVar(d);
        });

        ctx.readDoubleVar(d);
    }
}