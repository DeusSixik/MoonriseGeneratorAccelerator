package dev.sixik.density_compiller.compiler.utils;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerUtils {

    public static void arrayForFill(
            DensityCompilerContext ctx,
            int destArrayVar,
            double value
    ) {
        arrayForI(ctx, destArrayVar, (i) -> {
            final MethodVisitor mv = ctx.mv();
            mv.visitVarInsn(ALOAD, destArrayVar);   // Array
            mv.visitVarInsn(ILOAD, i);              // Index
            mv.visitLdcInsn(value);                 // Value
            mv.visitInsn(DASTORE);                  // Store double
        });
    }

    public static void arrayForI(
            DensityCompilerContext ctx,
            int destArrayVar,
            Consumer<Integer> iteration
    ) {
        /*
            We get the same length variable for the entire method.
         */
        int lenVar = ctx.getOrComputeLength(destArrayVar);

        /*
            The i counter must still be unique for each loop
            so that there are no nesting conflicts, but the JIT often collapses them on its own.
         */
        int iVar = ctx.allocateLocalVarIndex();

        final MethodVisitor mv = ctx.mv();

        // int i = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, iVar);

        Label startLoop = new Label();
        Label endLoop = new Label();

        mv.visitLabel(startLoop);

        // if (i >= len) break
        mv.visitVarInsn(ILOAD, iVar);
        mv.visitVarInsn(ILOAD, lenVar); // Using the general lenVar
        mv.visitJumpInsn(IF_ICMPGE, endLoop);

        iteration.accept(iVar);

        mv.visitIincInsn(iVar, 1);
        mv.visitJumpInsn(GOTO, startLoop);

        mv.visitLabel(endLoop);
    }

    public static void arrayFillD(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC,
                "java/util/Arrays",
                "fill",
                "([DD)V",
                false);
    }

    public static void min(MethodVisitor visitor) {
        visitor.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
    }

    public static void max(MethodVisitor visitor) {
        visitor.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
    }

    public static void clamp(MethodVisitor visitor) {
        visitor.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Mth", "clamp", "(DDD)D", false);
    }

    public static void abs(MethodVisitor visitor) {
        visitor.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
    }

    public static void clampedMap(MethodVisitor visitor) {
        visitor.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Mth", "clampedMap", "(DDDDD)D", false);
    }

    /**
     * Генерирует: val > 0 ? val : val * factor
     * Оптимизирует случай, если мы точно знаем, что число всегда отрицательное.
     */
    public static void compileNegativeFactor(MethodVisitor mv, double factor, DensityFunction input) {
        // Если вход всегда отрицательный (или 0), ветвление не нужно — всегда умножаем.
        if (input.maxValue() <= 0.0) {
            mv.visitLdcInsn(factor);
            mv.visitInsn(DMUL);
            return;
        }

        // Стандартное ветвление
        Label end = new Label();
        Label isNegative = new Label();

        mv.visitInsn(DUP2);      // [val, val]
        mv.visitInsn(DCONST_0);  // [val, val, 0.0]
        mv.visitInsn(DCMPG);     // Сравниваем
        mv.visitJumpInsn(IFLT, isNegative); // val < 0 ? goto isNegative

        // Positive: skip
        mv.visitJumpInsn(GOTO, end);

        mv.visitLabel(isNegative);
        // [val] -> [val * factor]
        mv.visitLdcInsn(factor);
        mv.visitInsn(DMUL);

        mv.visitLabel(end);
    }

    /**
     * Реализует функцию Squeeze:
     * double e = clamp(val, -1.0, 1.0);
     * return e / 2.0 - e * e * e / 24.0;
     */
    public static void compileSqueeze(MethodVisitor mv, PipelineAsmContext ctx, boolean needsClamp) {
        // 1. Применяем Clamp, если диапазон входа выходит за [-1, 1]
        if (needsClamp) {
            mv.visitLdcInsn(1.0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            mv.visitLdcInsn(-1.0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }

        // Stack: [e]
        // Формула: e * 0.5 - (e^3) * 0.041666...

        // Чтобы не жонглировать стеком (для куба нужно 3 копии, для первой части еще одна),
        // проще и БЫСТРЕЕ сохранить 'e' в локальную переменную.
        // ASM позволяет выделить переменную "на лету".

        int eVar = ctx.newLocalDouble();
        mv.visitVarInsn(DSTORE, eVar); // Stack: []

        // Term 1: e * 0.5
        mv.visitVarInsn(DLOAD, eVar);
        mv.visitLdcInsn(0.5);
        mv.visitInsn(DMUL); // Stack: [term1]

        // Term 2: e * e * e * (1/24)
        mv.visitVarInsn(DLOAD, eVar);
        mv.visitVarInsn(DLOAD, eVar);
        mv.visitInsn(DMUL); // e^2
        mv.visitVarInsn(DLOAD, eVar);
        mv.visitInsn(DMUL); // e^3

        mv.visitLdcInsn(1.0 / 24.0);
        mv.visitInsn(DMUL); // Stack: [term1, term2]

        // Result: term1 - term2
        mv.visitInsn(DSUB); // Stack: [result]

        // Освобождаем слот (виртуально, если у тебя есть менеджер переменных)
        // ctx.freeLocal(eVar);
    }

    public boolean isYInvariant(DensityFunction f) {
        // Константы всегда инвариантны
        if (f instanceof DensityFunctions.Constant) return true;

        // Шум инвариантен, если его аргументы (X, Y, Z) инвариантны
        if (f instanceof DensityFunctions.Noise n) {
            // В майнкрафте Noise обычно берет стандартные X, Y, Z.
            // Если Y зафиксирован (как в ShiftedNoise с нулевым ShiftY), он инвариантен.
            return false; // По умолчанию шум зависит от Y
        }

        // Рекурсивная проверка для операций
        if (f instanceof DensityFunctions.TwoArgumentSimpleFunction ap2) {
            return isYInvariant(ap2.argument1()) && isYInvariant(ap2.argument2());
        }

        return false;
    }

    public static boolean isConst(DensityFunction f) {
        return f instanceof DensityFunctions.Constant || f instanceof DensityFunctions.BlendAlpha || f instanceof DensityFunctions.BlendOffset || f instanceof DensityFunctions.BeardifierMarker;
    }

    public static boolean isConst(DensityFunction f, double val) {
        if(f instanceof DensityFunctions.Constant c && c.value() == val)
            return true;

        if(f instanceof DensityFunctions.BlendAlpha c && c.minValue() == val)
            return true;

        if(f instanceof DensityFunctions.BeardifierMarker marker && marker.maxValue() == val)
            return true;

        return f instanceof DensityFunctions.BlendOffset c && c.minValue() == val;
    }

    public static double getConst(DensityFunction f) {

        if(f instanceof DensityFunctions.Constant constant)
            return constant.value();

        if(f instanceof DensityFunctions.BlendOffset blendAlpha)
            return blendAlpha.minValue();

        if(f instanceof DensityFunctions.BlendAlpha blendAlpha)
            return blendAlpha.minValue();

        if(f instanceof DensityFunctions.BeardifierMarker marker)
            return marker.maxValue();

        throw new NullPointerException("Can't get constant from " + f.getClass().getName() + " !");
    }
}
