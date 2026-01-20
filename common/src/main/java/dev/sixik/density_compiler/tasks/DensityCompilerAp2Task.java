package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import static dev.sixik.density_compiler.utils.DensityCompilerUtils.getConst;
import static dev.sixik.density_compiler.utils.DensityCompilerUtils.isConst;
import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerAp2Task extends DensityCompilerTask<DensityFunctions.Ap2> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Ap2 node, Step step) {
        switch (step) {
            case Prepare -> this.prepare(ctx, node);
            case PostPrepare -> this.postPrepare(ctx, node);
            case CalculateSize -> this.calculateSize(ctx, node);
            case Compute -> this.compute(ctx, node);
        }
    }

    private void compute(DCAsmContext ctx, DensityFunctions.Ap2 node) {

        final DensityFunction arg1 = node.argument1();
        final DensityFunction arg2 = node.argument2();
        final DensityFunctions.TwoArgumentSimpleFunction.Type type = node.type();

        if (isConst(arg1) && isConst(arg2)) {
            final double v1 = getConst(arg1);
            final double v2 = getConst(arg2);

            double result = switch (type) {
                case ADD -> v1 + v2;
                case MUL -> v1 * v2;
                case MIN -> Math.min(v1, v2);
                case MAX -> Math.max(v1, v2);
            };
            ctx.mv().visitLdcInsn(result);
            return;
        }

        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            if (isConst(arg1, 0.0)) {
                ctx.readNode(arg2, Step.Compute);
                return;
            }

            if (isConst(arg2, 0.0)) {
                ctx.readNode(arg1, Step.Compute);
                return;
            }
        }

        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) {
                ctx.push(0);
                return;
            }
            if (isConst(arg1, 1.0)) {
                ctx.readNode(arg2, Step.Compute);
                return;
            }
            if (isConst(arg2, 1.0)) {
                ctx.readNode(arg1, Step.Compute);
                return;
            }

            if (arg1 == arg2 || arg1.equals(arg2)) {
                ctx.readNode(arg1, Step.Compute);
                ctx.mv().visitInsn(DUP2);
                ctx.mv().visitInsn(DMUL);
                return;
            }
        }

        if(type == DensityFunctions.TwoArgumentSimpleFunction.Type.MAX) {
            if (arg1.minValue() >= arg2.maxValue()) {
                ctx.readNode(arg1, Step.Compute);
                return;
            }
            if (arg2.minValue() >= arg1.maxValue()) {
                ctx.readNode(arg2, Step.Compute);
                return;
            }
        }

        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
            if (arg1.maxValue() <= arg2.minValue()) {
                ctx.readNode(arg1, Step.Compute);
                return;
            }
            if (arg2.maxValue() <= arg1.minValue()) {
                ctx.readNode(arg2, Step.Compute);
                return;
            }
        }

        ctx.readNode(arg1, Step.Compute);
        ctx.readNode(arg2, Step.Compute);
        applyOp(ctx, type);
    }

    private void applyOp(DCAsmContext ctx, DensityFunctions.TwoArgumentSimpleFunction.Type type) {
        switch (type) {
            case ADD -> ctx.mv().visitInsn(DADD);
            case MUL -> ctx.mv().visitInsn(DMUL);
            case MIN -> ctx.mv().visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            case MAX -> ctx.mv().visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }
    }

    private void calculateSize(DCAsmContext ctx, DensityFunctions.Ap2 node) {
        ctx.readNode(node.argument1(), Step.CalculateSize);
        ctx.readNode(node.argument2(), Step.CalculateSize);
    }

    private void postPrepare(DCAsmContext ctx, DensityFunctions.Ap2 node) {
        ctx.readNode(node.argument1(), Step.PostPrepare);
        ctx.readNode(node.argument2(), Step.PostPrepare);
    }

    private void prepare(DCAsmContext ctx, DensityFunctions.Ap2 node) {
        ctx.readNode(node.argument1(), Step.Prepare);
        ctx.readNode(node.argument2(), Step.Prepare);
    }
}
