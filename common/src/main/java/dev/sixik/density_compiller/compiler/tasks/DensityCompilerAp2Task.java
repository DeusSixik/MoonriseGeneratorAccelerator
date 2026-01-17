package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils.getConst;
import static dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils.isConst;
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
        ctx.comment("Ap2: " + node.type().name());

        DensityFunction arg1 = node.argument1();
        DensityFunction arg2 = node.argument2();
        var type = node.type();

        // --- ОПТИМИЗАЦИЯ 1: Constant Folding ---
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

        // --- ОПТИМИЗАЦИЯ 2: Identity & Zero Eliminator ---
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            if (isConst(arg1, 0.0)) { ctx.visitNodeCompute(arg2); return; }
            if (isConst(arg2, 0.0)) { ctx.visitNodeCompute(arg1); return; }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) { mv.visitInsn(DCONST_0); return; }
            if (isConst(arg1, 1.0)) { ctx.visitNodeCompute(arg2); return; }
            if (isConst(arg2, 1.0)) { ctx.visitNodeCompute(arg1); return; }

            // --- ОПТИМИЗАЦИЯ 3: Squaring (x * x) ---
            // Если это умножение одного и того же объекта (частый паттерн возведения в степень)
            if (arg1 == arg2 || arg1.equals(arg2)) {
                machine.pushStack(node.getClass(), arg1.getClass());
                ctx.visitNodeCompute(arg1); // Генерируем код ОДИН раз
                machine.popStack();

                mv.visitInsn(DUP2); // Дублируем double на стеке (Stack: val, val)
                mv.visitInsn(DMUL); // Умножаем: val * val
                return;
            }
        }

        // --- ОПТИМИЗАЦИЯ 4: Range-Based Pruning (MIN/MAX) ---
        // Если диапазоны значений не пересекаются, MIN/MAX можно вычислить статически
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MAX) {
            // Если min(arg1) >= max(arg2), то arg1 всегда больше
            if (arg1.minValue() >= arg2.maxValue()) {
                ctx.visitNodeCompute(arg1);
                return;
            }
            // Если min(arg2) >= max(arg1), то arg2 всегда больше
            if (arg2.minValue() >= arg1.maxValue()) {
                ctx.visitNodeCompute(arg2);
                return;
            }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
            // Если max(arg1) <= min(arg2), то arg1 всегда меньше
            if (arg1.maxValue() <= arg2.minValue()) {
                ctx.visitNodeCompute(arg1);
                return;
            }
            // Если max(arg2) <= min(arg1), то arg2 всегда меньше
            if (arg2.maxValue() <= arg1.minValue()) {
                ctx.visitNodeCompute(arg2);
                return;
            }
        }

        // --- ГЕНЕРАЦИЯ ---
        // Порядок вычисления важен. Если один из аргументов - это загрузка локальной переменной (Hoisted),
        // а другой - сложное вычисление, лучше сначала вычислить сложное, а потом загрузить легкое.
        // Но для стековой машины JVM порядок (arg1, arg2) безопаснее всего.

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
            // Math.min/max - это интринсики (HotSpot заменяет их на vminsd/vmaxsd),
            // поэтому ручной if/else писать не нужно, это будет медленнее.
            case MIN -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            case MAX -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }
    }
}
