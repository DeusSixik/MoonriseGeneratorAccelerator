package dev.sixik.density_compiller.compiler.utils;

import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.INVOKESTATIC;

public class DensityCompilerMath {

    public static void min(MethodVisitor visitor) {
        visitor.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
    }

    public static void max(MethodVisitor visitor) {
        visitor.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
    }
}
