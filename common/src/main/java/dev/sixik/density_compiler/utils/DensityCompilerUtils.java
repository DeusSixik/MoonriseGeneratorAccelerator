package dev.sixik.density_compiler.utils;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.INVOKESTATIC;

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
}
