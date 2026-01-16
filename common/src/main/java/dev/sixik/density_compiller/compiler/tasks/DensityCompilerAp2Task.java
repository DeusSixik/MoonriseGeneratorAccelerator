package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;


public class DensityCompilerAp2Task extends DensityCompilerTask<DensityFunctions.Ap2> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.Ap2 node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.argument1().getClass());
        ctx.visitNodeCompute(node.argument1(), PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.argument2().getClass());
        ctx.visitNodeCompute(node.argument2(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.Ap2 node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.argument1().getClass());
        ctx.visitNodeCompute(node.argument1(), POST_PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.argument2().getClass());
        ctx.visitNodeCompute(node.argument2(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Ap2 node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        DensityFunction arg1 = node.argument1();
        DensityFunction arg2 = node.argument2();
        var type = node.type();

        // 1. Constant Folding (Оба константы)
        if (isConst(arg1) && isConst(arg2)) {
            double v1 = getConst(arg1);
            double v2 = getConst(arg2);
            double result = switch (type) {
                case ADD -> v1 + v2;
                case MUL -> v1 * v2;
                case MIN -> Math.min(v1, v2);
                case MAX -> Math.max(v1, v2);
            };
            mv.visitLdcInsn(result);
            return;
        }

        // 2. Identity & Zero checks (Add 0, Mul 1, Mul 0)
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            if (isConst(arg1, 0.0)) {
                ctx.visitNodeCompute(arg2);
                return;
            }
            if (isConst(arg2, 0.0)) {
                ctx.visitNodeCompute(arg1);
                return;
            }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) {
                mv.visitInsn(DCONST_0);
                return;
            }
            if (isConst(arg1, 1.0)) {
                ctx.visitNodeCompute(arg2);
                return;
            }
            if (isConst(arg2, 1.0)) {
                ctx.visitNodeCompute(arg1);
                return;
            }
        }

        // 3. Стандартное вычисление
        machine.pushStack(node.getClass(), arg1.getClass());
        ctx.visitNodeCompute(arg1);
        machine.popStack();

        machine.pushStack(node.getClass(), arg2.getClass());
        ctx.visitNodeCompute(arg2);
        machine.popStack();

        applyOp(mv, type);
    }

    private void applyOp(MethodVisitor mv, DensityFunctions.TwoArgumentSimpleFunction.Type type) {
        switch (type) {
            case ADD -> mv.visitInsn(DADD);
            case MUL -> mv.visitInsn(DMUL);
            case MIN -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            case MAX -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }
    }

    // Хелперы
    private boolean isConst(DensityFunction f) {
        return f instanceof DensityFunctions.Constant;
    }

    private boolean isConst(DensityFunction f, double val) {
        return f instanceof DensityFunctions.Constant c && c.value() == val;
    }

    private double getConst(DensityFunction f) {
        return ((DensityFunctions.Constant) f).value();
    }
}
