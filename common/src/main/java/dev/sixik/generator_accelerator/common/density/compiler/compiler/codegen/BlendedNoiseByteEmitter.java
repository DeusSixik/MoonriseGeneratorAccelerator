package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
/**
 * Emits {@link IRNode.InlinedBlendedNoise} as straight-line bytecode mirroring
 * Minecraft 1.21.x {@code BlendedNoise#compute} (unrolled main/limit octaves, 5-arg
 * {@link net.minecraft.world.level.levelgen.synth.ImprovedNoise#noise}).
 */
public final class BlendedNoiseByteEmitter {
    @FunctionalInterface
    public interface DSlot { int next(); }

    private static final String MTH = "net/minecraft/util/Mth";
    private static final String RT = "dev/sixik/generator_accelerator/common/density/compiler/compiler/runtime/Runtime";
    private static final String IN = "net/minecraft/world/level/levelgen/synth/ImprovedNoise";
    private static final String IND = "L" + IN + ";";
    private static final String N5 = "(DDDDD)D";

    private BlendedNoiseByteEmitter() {}

    public static void emit(
            MethodVisitor mv,
            String classInternalName,
            ConstantPool pool,
            int bSpec,
            boolean checkCast,
            DSlot d) {
        var sp = pool.blendedNoiseSpec(bSpec);
        // d0,d1,d2
        int s0 = d.next();
        int s2 = d.next();
        int s4 = d.next();
        // d3..d5
        int s6 = d.next();
        int s8 = d.next();
        int s10 = d.next();
        // d6,d7
        int s12 = d.next();
        int s14 = d.next();
        // d8,d9,d10,d16
        int sD8 = d.next();
        int sD9 = d.next();
        int sD10 = d.next();
        int sD16 = d.next();
        // d0 = bx * xzMul
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.I2D);
        mv.visitLdcInsn(sp.xzMultiplier());
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, s0);
        // d1
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.I2D);
        mv.visitLdcInsn(sp.yMultiplier());
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, s2);
        // d2
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.I2D);
        mv.visitLdcInsn(sp.xzMultiplier());
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, s4);

        // d3
        mv.visitVarInsn(Opcodes.DLOAD, s0);
        mv.visitLdcInsn(sp.xzFactor());
        mv.visitInsn(Opcodes.DDIV);
        mv.visitVarInsn(Opcodes.DSTORE, s6);
        // d4
        mv.visitVarInsn(Opcodes.DLOAD, s2);
        mv.visitLdcInsn(sp.yFactor());
        mv.visitInsn(Opcodes.DDIV);
        mv.visitVarInsn(Opcodes.DSTORE, s8);
        // d5
        mv.visitVarInsn(Opcodes.DLOAD, s4);
        mv.visitLdcInsn(sp.xzFactor());
        mv.visitInsn(Opcodes.DDIV);
        mv.visitVarInsn(Opcodes.DSTORE, s10);

        // d6 = yMult * smear, d7 = d6 / yF
        mv.visitLdcInsn(sp.yMultiplier());
        mv.visitLdcInsn(sp.smearScaleMultiplier());
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, s12);
        mv.visitVarInsn(Opcodes.DLOAD, s12);
        mv.visitLdcInsn(sp.yFactor());
        mv.visitInsn(Opcodes.DDIV);
        mv.visitVarInsn(Opcodes.DSTORE, s14);

        // d10 = 0
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitVarInsn(Opcodes.DSTORE, sD10);

        // Main octaves 0..7
        for (int i = 0; i < BlendedNoiseSpec.MAIN_OCTAVES; i++) {
            double d11 = 1.0 / (1L << i);
            // GETFIELD
            loadThis(mv, classInternalName, checkCast);
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, Codegen.blendedFieldName(bSpec, 0, i), IND);
            mv.visitInsn(Opcodes.DUP);
            Label skip = new Label();
            Label after = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, skip);

            // stack: in
            // wrap(d3 * d11)
            loadScaled(mv, s6, d11);
            wrapAxis(mv, d);
            // wrap(d4 * d11)
            loadScaled(mv, s8, d11);
            wrapAxis(mv, d);
            // wrap(d5 * d11)
            loadScaled(mv, s10, d11);
            wrapAxis(mv, d);
            // d7 * d11
            loadScaled(mv, s14, d11);
            // d4 * d11  (4th/5th: yScale, yMax per vanilla d4 * d11)
            loadScaled(mv, s8, d11);

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN, "noise", N5, false);
            divideBy(mv, d11);
            // += d10
            mv.visitVarInsn(Opcodes.DLOAD, sD10);
            mv.visitInsn(Opcodes.DADD);
            mv.visitVarInsn(Opcodes.DSTORE, sD10);
            mv.visitJumpInsn(Opcodes.GOTO, after);
            // skip: pop null
            mv.visitLabel(skip);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(after);
        }

        // d16 = (d10 / 10 + 1) / 2
        mv.visitVarInsn(Opcodes.DLOAD, sD10);
        mv.visitLdcInsn(10.0);
        mv.visitInsn(Opcodes.DDIV);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitInsn(Opcodes.DADD);
        mv.visitLdcInsn(0.5);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, sD16);
        // clampedLerp(d8/512, d9/512, d16) always runs; vanilla skips min/max *loops* but
        // leaves d8/d9 at 0 on those paths — locals must be written on every control path.
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitVarInsn(Opcodes.DSTORE, sD8);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitVarInsn(Opcodes.DSTORE, sD9);

        Label skipAllMin = new Label();
        // if d16 >= 1 skip min
        mv.visitVarInsn(Opcodes.DLOAD, sD16);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitInsn(Opcodes.DCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, skipAllMin);

        for (int j = 0; j < BlendedNoiseSpec.LIMIT_OCTAVES; j++) {
            double d11 = 1.0 / (1L << j);
            // min
            loadThis(mv, classInternalName, checkCast);
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, Codegen.blendedFieldName(bSpec, 1, j), IND);
            mv.visitInsn(Opcodes.DUP);
            Label sskip = new Label();
            Label sa = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, sskip);

            wrap3(mv, s0, s2, s4, d11, d);
            // d15 = d6 * d11
            loadScaled(mv, s12, d11);
            // d1 * d11
            loadScaled(mv, s2, d11);

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN, "noise", N5, false);
            divideBy(mv, d11);
            mv.visitVarInsn(Opcodes.DLOAD, sD8);
            mv.visitInsn(Opcodes.DADD);
            mv.visitVarInsn(Opcodes.DSTORE, sD8);
            mv.visitJumpInsn(Opcodes.GOTO, sa);
            mv.visitLabel(sskip);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(sa);
        }
        mv.visitLabel(skipAllMin);

        Label skipAllMax = new Label();
        // if d16 <= 0 skip max
        mv.visitVarInsn(Opcodes.DLOAD, sD16);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCMPG);
        mv.visitJumpInsn(Opcodes.IFLE, skipAllMax);

        for (int j = 0; j < BlendedNoiseSpec.LIMIT_OCTAVES; j++) {
            double d11 = 1.0 / (1L << j);
            loadThis(mv, classInternalName, checkCast);
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, Codegen.blendedFieldName(bSpec, 2, j), IND);
            mv.visitInsn(Opcodes.DUP);
            Label sskip = new Label();
            Label sa = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, sskip);
            wrap3(mv, s0, s2, s4, d11, d);
            loadScaled(mv, s12, d11);
            loadScaled(mv, s2, d11);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN, "noise", N5, false);
            divideBy(mv, d11);
            mv.visitVarInsn(Opcodes.DLOAD, sD9);
            mv.visitInsn(Opcodes.DADD);
            mv.visitVarInsn(Opcodes.DSTORE, sD9);
            mv.visitJumpInsn(Opcodes.GOTO, sa);
            mv.visitLabel(sskip);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(sa);
        }
        mv.visitLabel(skipAllMax);

        // Mth.clampedLerp(d8/512, d9/512, d16) / 128
        mv.visitVarInsn(Opcodes.DLOAD, sD8);
        mv.visitLdcInsn(512.0);
        mv.visitInsn(Opcodes.DDIV);
        mv.visitVarInsn(Opcodes.DLOAD, sD9);
        mv.visitLdcInsn(512.0);
        mv.visitInsn(Opcodes.DDIV);
        mv.visitVarInsn(Opcodes.DLOAD, sD16);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MTH, "clampedLerp", "(DDD)D", false);
        mv.visitLdcInsn(128.0);
        mv.visitInsn(Opcodes.DDIV);
    }

    private static void loadThis(MethodVisitor mv, String classIn, boolean check) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (check) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, classIn);
        }
    }

    private static void wrap3(MethodVisitor mv, int s0, int s2, int s4, double d11, DSlot d) {
        loadScaled(mv, s0, d11);
        wrapAxis(mv, d);
        loadScaled(mv, s2, d11);
        wrapAxis(mv, d);
        loadScaled(mv, s4, d11);
        wrapAxis(mv, d);
    }

    private static void loadScaled(MethodVisitor mv, int slot, double factor) {
        mv.visitVarInsn(Opcodes.DLOAD, slot);
        if (Double.compare(factor, 1.0D) != 0) {
            mv.visitLdcInsn(factor);
            mv.visitInsn(Opcodes.DMUL);
        }
    }

    private static void divideBy(MethodVisitor mv, double divisor) {
        if (Double.compare(divisor, 1.0D) != 0) {
            mv.visitLdcInsn(divisor);
            mv.visitInsn(Opcodes.DDIV);
        }
    }

    private static void wrapAxis(MethodVisitor mv, DSlot d) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RT, "wrapAxis", "(D)D", false);
    }

}
