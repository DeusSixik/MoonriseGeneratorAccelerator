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

    public static void compileNegativeFactor(MethodVisitor mv, double factor) {
        Label labelEnd = new Label();

        mv.visitInsn(DUP2);          // [d, d]
        mv.visitInsn(DCONST_0);      // [d, d, 0.0]
        mv.visitInsn(DCMPL);         // [d, res_int]

        mv.visitJumpInsn(IFGT, labelEnd); // d > 0

        // Если d <= 0
        mv.visitLdcInsn(factor);     // [d, factor]
        mv.visitInsn(DMUL);          // [d * factor]

        mv.visitLabel(labelEnd);
    }

    public static void compileSqueeze(MethodVisitor mv, PipelineAsmContext ctx, boolean needsClamp) {
    /*
        e = clamp(d, -1, 1)
        Stack input: [d]
    */
        if (needsClamp) {
            mv.visitLdcInsn(-1.0);
            mv.visitLdcInsn(1.0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }
        // Stack: [e]

    /*
        Сохраняем 'e' в переменную, чтобы использовать дважды.
        Это избавляет от DUP2_X2 ада.
    */
        int varE = ctx.newLocalDouble();
        mv.visitInsn(DUP2);          // [e, e]
        mv.visitVarInsn(DSTORE, varE); // [e] -> varE = e

    /*
        1. Считаем e / 2.0
    */
        mv.visitLdcInsn(2.0);
        mv.visitInsn(DDIV);          // [e / 2.0]

    /*
        2. Считаем e^3 / 24.0
    */
        mv.visitVarInsn(DLOAD, varE); // [e/2.0, e]
        mv.visitInsn(DUP2);           // [e/2.0, e, e]
        mv.visitInsn(DUP2);           // [e/2.0, e, e, e]
        mv.visitInsn(DMUL);           // [e/2.0, e, e^2]
        mv.visitInsn(DMUL);           // [e/2.0, e^3]
        mv.visitLdcInsn(24.0);        // [e/2.0, e^3, 24.0]
        mv.visitInsn(DDIV);           // [e/2.0, e^3/24.0]

    /*
        3. Вычитаем: (e/2) - (e^3/24)
    */
        mv.visitInsn(DSUB);           // [result]
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
}
