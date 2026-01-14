package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerClampTask extends DensityCompilerTask<DensityFunctions.Clamp> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Clamp node, PipelineAsmContext ctx) {
        DensityFunction input = node.input();
        double cMin = node.minValue(); // Границы клэмпа
        double cMax = node.maxValue();

        double inMin = input.minValue(); // Границы входной функции
        double inMax = input.maxValue();

        // 1. Оптимизация: Константный результат
        // Если вход всегда меньше минимума -> результат всегда min
        if (inMax <= cMin) {
            mv.visitLdcInsn(cMin);
            return;
        }
        // Если вход всегда больше максимума -> результат всегда max
        if (inMin >= cMax) {
            mv.visitLdcInsn(cMax);
            return;
        }

        // 2. Оптимизация: Clamp не нужен (вход уже внутри границ)
        if (inMin >= cMin && inMax <= cMax) {
            ctx.visitNodeCompute(input);
            return;
        }

        // 3. Частичный или полный Clamp
        ctx.visitNodeCompute(input);

        // Нужно ли обрезать сверху? (input может быть > max)
        boolean needMaxCheck = inMax > cMax;
        // Нужно ли обрезать снизу? (input может быть < min)
        boolean needMinCheck = inMin < cMin;

        if (needMaxCheck) {
            mv.visitLdcInsn(cMax);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
        }

        if (needMinCheck) {
            mv.visitLdcInsn(cMin);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }
    }

//    @Override
//    public void compileFill(MethodVisitor mv, DensityFunctions.Clamp node, PipelineAsmContext ctx, int destArrayVar) {
//        DensityFunction input = node.input();
//        double cMin = node.minValue();
//        double cMax = node.maxValue();
//
//        double inMin = input.minValue();
//        double inMax = input.maxValue();
//
//        // 1. Константа
//        if (inMax <= cMin) {
//            ctx.visitNodeFill(new DensityFunctions.Constant(cMin), destArrayVar);
//            return;
//        }
//        if (inMin >= cMax) {
//            ctx.visitNodeFill(new DensityFunctions.Constant(cMax), destArrayVar);
//            return;
//        }
//
//        // 2. Пропуск (Identity)
//        if (inMin >= cMin && inMax <= cMax) {
//            ctx.visitNodeFill(input, destArrayVar);
//            return;
//        }
//
//        // 3. Вычисление
//
//        // Сначала заливаем массив входом
//        ctx.visitNodeFill(input, destArrayVar);
//
//        boolean needMaxCheck = inMax > cMax;
//        boolean needMinCheck = inMin < cMin;
//
//        // In-place модификация
//        ctx.arrayForI(destArrayVar, (iVar) -> {
//            mv.visitVarInsn(ALOAD, destArrayVar);
//            mv.visitVarInsn(ILOAD, iVar);
//            mv.visitInsn(DUP2); // [Arr, I, Arr, I]
//
//            mv.visitInsn(DALOAD); // [Arr, I, Val]
//
//            // Применяем Math.min / Math.max только если реально нужно
//            if (needMaxCheck) {
//                mv.visitLdcInsn(cMax);
//                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
//            }
//
//            if (needMinCheck) {
//                mv.visitLdcInsn(cMin);
//                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
//            }
//
//            mv.visitInsn(DASTORE);
//        });
//    }
}
