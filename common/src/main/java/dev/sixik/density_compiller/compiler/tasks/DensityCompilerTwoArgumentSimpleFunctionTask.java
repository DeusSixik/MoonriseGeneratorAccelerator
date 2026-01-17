package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.stack.StackMachine;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;
import static dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils.*;

public class DensityCompilerTwoArgumentSimpleFunctionTask extends
        DensityCompilerTask<DensityFunctions.TwoArgumentSimpleFunction> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.TwoArgumentSimpleFunction node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();
        machine.pushStack(node.getClass(), node.argument1().getClass());
        ctx.visitNodeCompute(node.argument1(), PREPARE_COMPUTE);
        machine.popStack();
        machine.pushStack(node.getClass(), node.argument2().getClass());
        ctx.visitNodeCompute(node.argument2(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.TwoArgumentSimpleFunction node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();
        machine.pushStack(node.getClass(), node.argument1().getClass());
        ctx.visitNodeCompute(node.argument1(), POST_PREPARE_COMPUTE);
        machine.popStack();
        machine.pushStack(node.getClass(), node.argument2().getClass());
        ctx.visitNodeCompute(node.argument2(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv,
                                  DensityFunctions.TwoArgumentSimpleFunction node,
                                  PipelineAsmContext ctx
    ) {
        var machine = ctx.pipeline().stackMachine();

        DensityFunction arg1 = node.argument1();
        DensityFunction arg2 = node.argument2();
        var type = node.type();

        // 1. Constant Folding
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

        // 2. Identity & Zero Logic
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            if (isConst(arg1, 0.0)) { generate(ctx, node, arg2, machine); return; }
            if (isConst(arg2, 0.0)) { generate(ctx, node, arg1, machine); return; }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) { mv.visitInsn(DCONST_0); return; }
            if (isConst(arg1, 1.0)) { generate(ctx, node, arg2, machine); return; }
            if (isConst(arg2, 1.0)) { generate(ctx, node, arg1, machine); return; }

            // --- ОПТИМИЗАЦИЯ 3: SQUARING (x * x) ---
            // Самая важная оптимизация для производительности шума.
            // Если мы умножаем функцию саму на себя, вычисляем её ОДИН раз и дублируем на стеке.
            if (arg1 == arg2 || arg1.equals(arg2)) {
                generate(ctx, node, arg1, machine); // Stack: [val]
                mv.visitInsn(DUP2);                 // Stack: [val, val]
                mv.visitInsn(DMUL);                 // Stack: [val * val]
                return;
            }
        }

        // --- ОПТИМИЗАЦИЯ 4: RANGE PRUNING (MIN/MAX) ---
        // Статическое удаление веток, если диапазоны не пересекаются
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MAX) {
            // Если min(A) >= max(B), то результат всегда A
            if (arg1.minValue() >= arg2.maxValue()) { generate(ctx, node, arg1, machine); return; }
            // Если min(B) >= max(A), то результат всегда B
            if (arg2.minValue() >= arg1.maxValue()) { generate(ctx, node, arg2, machine); return; }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
            // Если max(A) <= min(B), то результат всегда A
            if (arg1.maxValue() <= arg2.minValue()) { generate(ctx, node, arg1, machine); return; }
            // Если max(B) <= min(A), то результат всегда B
            if (arg2.maxValue() <= arg1.minValue()) { generate(ctx, node, arg2, machine); return; }
        }

        // Стандартная генерация
        generate(ctx, node, arg1, machine);
        generate(ctx, node, arg2, machine);

        switch (type) {
            case ADD -> mv.visitInsn(DADD);
            case MUL -> mv.visitInsn(DMUL);
            case MIN -> DensityCompilerUtils.min(mv);
            case MAX -> DensityCompilerUtils.max(mv);
        }
    }

    // Хелпер, чтобы уменьшить дублирование кода пуша в стек-машину
    private void generate(PipelineAsmContext ctx, DensityFunction owner, DensityFunction child, StackMachine machine) {
        machine.pushStack(owner.getClass(), child.getClass());
        ctx.visitNodeCompute(child);
        machine.popStack();
    }
}
