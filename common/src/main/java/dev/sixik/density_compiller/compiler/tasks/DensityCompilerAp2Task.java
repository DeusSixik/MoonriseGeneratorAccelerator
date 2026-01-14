package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;


public class DensityCompilerAp2Task extends DensityCompilerTask<DensityFunctions.Ap2> {
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Ap2 node, DensityCompilerContext ctx) {
        ctx.compileNodeCompute(mv, node.argument1()); // Put double (+2 slots)
        ctx.compileNodeCompute(mv, node.argument2()); // Put double (+2 slots)

        switch (node.type()) {
            case ADD -> mv.visitInsn(DADD);
            case MUL -> mv.visitInsn(DMUL);
            case MIN -> DensityCompilerUtils.min(mv);
            case MAX -> DensityCompilerUtils.max(mv);
        }
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Ap2 node, DensityCompilerContext ctx, int destArrayVar) {

        /*
            Optimization: Constant Folding (if both arguments are constants)
         */
        if (node.argument1() instanceof DensityFunctions.Constant c1 &&
                node.argument2() instanceof DensityFunctions.Constant c2) {

            double result = switch (node.type()) {
                case ADD -> c1.value() + c2.value();
                case MUL -> c1.value() * c2.value();
                case MIN -> Math.min(c1.value(), c2.value());
                case MAX -> Math.max(c1.value(), c2.value());
            };

           /*
                Just fill the array with the result and exit
            */
            ctx.compileNodeFill(new DensityFunctions.Constant(result), destArrayVar);
            return;
        }

        DensityFunction arg1 = node.argument1();
        DensityFunction arg2 = node.argument2();

        // 1. Сначала заполняем массив первым аргументом
        ctx.compileNodeFill(arg1, destArrayVar);

        // 2. Быстрый путь для констант (без ленивых вычислений)
        if (arg2 instanceof DensityFunctions.Constant c) {
            double val = c.value();
            ctx.arrayForI(destArrayVar, (iVar) -> {
                mv.visitVarInsn(ALOAD, destArrayVar);
                mv.visitVarInsn(ILOAD, iVar);
                mv.visitInsn(DUP2);
                mv.visitInsn(DALOAD);
                mv.visitLdcInsn(val);
                applyOp(mv, node.type());
                mv.visitInsn(DASTORE);
            });
            return;
        }

        // 3. Полная логика с Lazy Evaluation и исправленным стеком
        ctx.arrayForI(destArrayVar, (iVar) -> {
            // ВАЖНО: Загружаем данные для DASTORE в самом начале!
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            // Берем текущее значение из массива
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD); // Стек: [Array, Index, Val1]

            Label end = new Label();

            // Проверки Short-circuit
            switch (node.type()) {
                case MUL -> {
                    mv.visitInsn(DUP2);
                    mv.visitInsn(DCONST_0);
                    mv.visitInsn(DCMPL);
                    mv.visitJumpInsn(IFEQ, end); // Если 0, оставляем 0 на стеке и прыгаем
                }
                case MIN -> {
                    mv.visitInsn(DUP2);
                    mv.visitLdcInsn(arg2.minValue());
                    mv.visitInsn(DCMPL);
                    mv.visitJumpInsn(IFLT, end); // Если Val1 < min, оставляем Val1 и прыгаем
                }
                case MAX -> {
                    mv.visitInsn(DUP2);
                    mv.visitLdcInsn(arg2.maxValue());
                    mv.visitInsn(DCMPG);
                    mv.visitJumpInsn(IFGT, end); // Если Val1 > max, оставляем Val1 и прыгаем
                }
            }

            // Если не прыгнули — вычисляем второе значение
            ctx.startLoop();
            int loopCtx = ctx.getOrAllocateLoopContext(iVar);
            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(loopCtx);

            ctx.compileNodeCompute(arg2); // Стек: [Array, Index, Val1, Val2]

            ctx.setCurrentContextVar(oldCtx);

            // Выполняем операцию
            applyOp(mv, node.type()); // Стек: [Array, Index, Result]

            mv.visitLabel(end);
            // Теперь и после вычислений, и после прыжка на стеке всегда: [Array, Index, Result]
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
}
