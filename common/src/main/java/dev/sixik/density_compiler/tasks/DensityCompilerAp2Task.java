package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;

import static dev.sixik.density_compiler.utils.DensityCompilerUtils.getConst;
import static dev.sixik.density_compiler.utils.DensityCompilerUtils.isConst;
import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerAp2Task extends DensityCompilerTask<DensityFunctions.Ap2> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Ap2 node, Step step) {

        if (step != Step.Compute) {
            ctx.readNode(node.argument1(), step);
            ctx.readNode(node.argument2(), step);
        } else compute(ctx, node);
    }

    private void compute(DCAsmContext ctx, DensityFunctions.Ap2 node) {
        int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        final DensityFunction arg1 = node.argument1();
        final DensityFunction arg2 = node.argument2();
        final DensityFunctions.TwoArgumentSimpleFunction.Type type = node.type();

        if (isConst(arg1) && isConst(arg2)) {
            double result = switch (type) {
                case ADD -> getConst(arg1) + getConst(arg2);
                case MUL -> getConst(arg1) * getConst(arg2);
                case MIN -> Math.min(getConst(arg1), getConst(arg2));
                case MAX -> Math.max(getConst(arg1), getConst(arg2));
            };
            ctx.push(result);
            return;
        }

        if (canSimplify(ctx, node, arg1, arg2, type)) return;

        ctx.readNode(arg1, Step.Compute);
        ctx.readNode(arg2, Step.Compute);
        applyOp(ctx, type);

        int id = ctx.mv().newLocal(Type.DOUBLE_TYPE);
        ctx.mv().dup2();
        ctx.mv().storeLocal(id);
        ctx.setVariable(node, id);
    }

    private void applyOp(DCAsmContext ctx, DensityFunctions.TwoArgumentSimpleFunction.Type type) {
        switch (type) {
            case ADD -> ctx.mv().visitInsn(DADD);
            case MUL -> ctx.mv().visitInsn(DMUL);
            case MIN -> ctx.mv().visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            case MAX -> ctx.mv().visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }
    }

    private boolean canSimplify(DCAsmContext ctx, DensityFunctions.Ap2 node, DensityFunction arg1, DensityFunction arg2, DensityFunctions.TwoArgumentSimpleFunction.Type type) {
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            if (isConst(arg1, 0.0)) {
                ctx.readNode(arg2, Step.Compute);
                return true;
            }
            if (isConst(arg2, 0.0)) {
                ctx.readNode(arg1, Step.Compute);
                return true;
            }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) {
                ctx.push(0.0);
                return true;
            }
            if (isConst(arg1, 1.0)) {
                ctx.readNode(arg2, Step.Compute);
                return true;
            }
            if (isConst(arg2, 1.0)) {
                ctx.readNode(arg1, Step.Compute);
                return true;
            }
            if (arg1 == arg2 || arg1.equals(arg2)) {
                ctx.readNode(arg1, Step.Compute);
                ctx.mv().dup2();
                ctx.mv().visitInsn(DMUL);
                return true;
            }
        }

        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MAX) {
            if (arg1.minValue() >= arg2.maxValue()) {
                ctx.readNode(arg1, Step.Compute);
                return true;
            }
            if (arg2.minValue() >= arg1.maxValue()) {
                ctx.readNode(arg2, Step.Compute);
                return true;
            }
        }

        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
            if (arg1.maxValue() <= arg2.minValue()) {
                ctx.readNode(arg1, Step.Compute);
                return true;
            }
            if (arg2.maxValue() <= arg1.minValue()) {
                ctx.readNode(arg2, Step.Compute);
                return true;
            }
        }

        return false;
    }
}
