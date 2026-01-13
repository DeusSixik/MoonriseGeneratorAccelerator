package dev.sixik.density_compiller.compiler.utils;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMath {

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

    public static void compileNegativeFactor(MethodVisitor mv, double factor) {
        mv.visitInsn(DUP2);      // [d, d]
        mv.visitInsn(DCONST_0);  // [d, d, 0.0]
        mv.visitInsn(DCMPL);     // d == 0.0

        Label labelEnd = new Label();
        mv.visitJumpInsn(IFGT, labelEnd); // d > 0

        mv.visitLdcInsn(factor); // [d, factor]
        mv.visitInsn(DMUL);   // [d * factor]

        mv.visitLabel(labelEnd);
    }

    public static void compileSqueeze(MethodVisitor mv) {
        // e = clamp(d, -1, 1)
        mv.visitLdcInsn(-1.0);
        mv.visitLdcInsn(1.0);
        clamp(mv);

        mv.visitInsn(DUP2); // [e, e]

        mv.visitLdcInsn(2.0);
        mv.visitInsn(DDIV); // [e, e/2]

        mv.visitInsn(SWAP + 1); // double swap

        mv.visitVarInsn(DSTORE, 2); // save e/2 to slot 2

        // e * e * e / 24.0
        mv.visitInsn(DUP2);
        mv.visitInsn(DUP2);
        mv.visitInsn(DMUL);
        mv.visitInsn(DMUL);     // e^3
        mv.visitLdcInsn(24.0);
        mv.visitInsn(DDIV);     // (e^3 / 24)

        mv.visitVarInsn(DLOAD, 2); // load e/2
        mv.visitInsn(SWAP + 1);    // swap to (e/2 - e^3/24)
        mv.visitInsn(DSUB);
    }
}
