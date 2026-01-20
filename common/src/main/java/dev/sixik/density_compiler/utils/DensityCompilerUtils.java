package dev.sixik.density_compiler.utils;

import dev.sixik.density_compiler.DCAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerUtils {

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

    /**
     * Генерирует: val > 0 ? val : val * factor
     * Оптимизирует случай, если мы точно знаем, что число всегда отрицательное.
     */
    public static void compileNegativeFactor(MethodVisitor mv, double factor, DensityFunction input) {
        // Если вход всегда отрицательный (или 0), ветвление не нужно — всегда умножаем.
        if (input.maxValue() <= 0.0) {
            mv.visitLdcInsn(factor);
//            ctx.mv().math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
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
    public static void compileSqueeze(MethodVisitor mv, DCAsmContext ctx, boolean needsClamp) {
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

        int eVar = ctx.mv().newLocal(Type.DOUBLE_TYPE);
        ctx.mv().storeLocal(eVar);


        // Term 1: e * 0.5
//        mv.visitVarInsn(DLOAD, eVar);
        ctx.mv().loadLocal(eVar);
        mv.visitLdcInsn(0.5);
        ctx.mv().math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
//        mv.visitInsn(DMUL); // Stack: [term1]

        // Term 2: e * e * e * (1/24)
        ctx.mv().loadLocal(eVar);
        ctx.mv().loadLocal(eVar);
//        mv.visitVarInsn(DLOAD, eVar);
//        mv.visitVarInsn(DLOAD, eVar);
        ctx.mv().math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
//        mv.visitInsn(DMUL); // e^2
        ctx.mv().loadLocal(eVar);
//        mv.visitVarInsn(DLOAD, eVar);
//        mv.visitInsn(DMUL); // e^3
        ctx.mv().math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);

        mv.visitLdcInsn(1.0 / 24.0);
        ctx.mv().math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
//        mv.visitInsn(DMUL); // Stack: [term1, term2]

        // Result: term1 - term2
//        mv.visitInsn(DSUB); // Stack: [result]
        ctx.mv().math(GeneratorAdapter.SUB, Type.DOUBLE_TYPE);

        // Освобождаем слот (виртуально, если у тебя есть менеджер переменных)
        // ctx.freeLocal(eVar);
    }
}
