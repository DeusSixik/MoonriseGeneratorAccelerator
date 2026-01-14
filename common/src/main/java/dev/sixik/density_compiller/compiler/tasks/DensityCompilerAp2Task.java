package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;


public class DensityCompilerAp2Task extends DensityCompilerTask<DensityFunctions.Ap2> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Ap2 node, PipelineAsmContext ctx) {
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
            if (isConst(arg1, 0.0)) { ctx.visitNodeCompute(arg2); return; }
            if (isConst(arg2, 0.0)) { ctx.visitNodeCompute(arg1); return; }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) { mv.visitInsn(DCONST_0); return; }
            if (isConst(arg1, 1.0)) { ctx.visitNodeCompute(arg2); return; }
            if (isConst(arg2, 1.0)) { ctx.visitNodeCompute(arg1); return; }
        }

        // 3. Стандартное вычисление
        ctx.visitNodeCompute(arg1);
        ctx.visitNodeCompute(arg2);

        applyOp(mv, type);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Ap2 node, PipelineAsmContext ctx, int destArrayVar) {
        DensityFunction arg1 = node.argument1();
        DensityFunction arg2 = node.argument2();
        var type = node.type();

        // --- Оптимизация 1: Identity & Zero (Пропуск работы) ---
        if ((type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD && (isConst(arg1, 0.0) || isConst(arg2, 0.0))) ||
                (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL && (isConst(arg1, 1.0) || isConst(arg2, 1.0)))) {
            DensityFunction nonConst = isConst(arg1) ? arg2 : arg1;
            ctx.visitNodeFill(nonConst, destArrayVar);
            return;
        }

        // Mul 0 -> Заливаем нулями
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL && (isConst(arg1, 0.0) || isConst(arg2, 0.0))) {
            ctx.visitNodeFill(new DensityFunctions.Constant(0.0), destArrayVar);
            return;
        }

        // --- Оптимизация 2: Scalar + Vector (Самая важная для Ap2) ---
        // Если один аргумент константа -> заливаем ВТОРОЙ (вектор), а потом применяем константу.
        // Это избегает вызова compute() внутри цикла для сложного аргумента.
        if (isConst(arg1) || isConst(arg2)) {
            double val = isConst(arg1) ? getConst(arg1) : getConst(arg2);
            DensityFunction vectorArg = isConst(arg1) ? arg2 : arg1;

            // 1. Заливаем массив сложным аргументом (Noise/Spline и т.д.)
            ctx.visitNodeFill(vectorArg, destArrayVar);

            // 2. Применяем скаляр
            ctx.arrayForI(destArrayVar, (iVar) -> {
                mv.visitVarInsn(ALOAD, destArrayVar);
                mv.visitVarInsn(ILOAD, iVar);
                mv.visitInsn(DUP2);   // [Arr, I, Arr, I]
                mv.visitInsn(DALOAD); // [Arr, I, ValVector]
                mv.visitLdcInsn(val); // [Arr, I, ValVector, ValScalar]

                // Для MIN/MAX/ADD порядок не важен (коммутативность).
                // Для MUL тоже.
                applyOp(mv, type);

                mv.visitInsn(DASTORE);
            });
            return;
        }

        // --- Оптимизация 3: Lazy Logic (Vector + Vector) ---
        // Если оба аргумента сложные, используем твою логику с Short-Circuiting.

        // 1. Заливаем первый аргумент
        ctx.visitNodeFill(arg1, destArrayVar);

        // 2. Цикл с проверкой условия
        ctx.arrayForI(destArrayVar, (iVar) -> {
            // Подготовка стека для DASTORE (Array, Index)
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            // Читаем значение первого аргумента
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD); // Stack: [Arr, Idx, Val1]

            Label end = new Label();

            // Проверки для выхода (Short-circuit)
            switch (type) {
                case MUL -> {
                    // if (Val1 == 0) skip
                    mv.visitInsn(DUP2);
                    mv.visitInsn(DCONST_0);
                    mv.visitInsn(DCMPL);
                    mv.visitJumpInsn(IFEQ, end);
                }
                case MIN -> {
                    // if (Val1 < min) skip (т.к. результат точно будет Val1 или меньше)
                    // Но Ap2 логика: return d < arg2.min() ? d : min(d, arg2)
                    mv.visitInsn(DUP2);
                    mv.visitLdcInsn(arg2.minValue());
                    mv.visitInsn(DCMPL);
                    mv.visitJumpInsn(IFLT, end);
                }
                case MAX -> {
                    // if (Val1 > max) skip
                    mv.visitInsn(DUP2);
                    mv.visitLdcInsn(arg2.maxValue());
                    mv.visitInsn(DCMPG);
                    mv.visitJumpInsn(IFGT, end);
                }
            }

            // Вычисляем Val2
            ctx.startLoop(); // Сбрасываем кэш контекста

            // Трюк с переключением контекста для правильной компиляции compute()
            int loopCtx = ctx.getOrAllocateLoopContext(iVar);
            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(loopCtx);

            ctx.visitNodeCompute(arg2); // Stack: [Arr, Idx, Val1, Val2]

            ctx.setCurrentContextVar(oldCtx);

            applyOp(mv, type); // Stack: [Arr, Idx, Result]

            mv.visitLabel(end);
            mv.visitInsn(DASTORE);
        });
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
        return ((DensityFunctions.Constant)f).value();
    }
}
