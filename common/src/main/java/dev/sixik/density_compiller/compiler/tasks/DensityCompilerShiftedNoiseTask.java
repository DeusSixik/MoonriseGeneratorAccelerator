package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.PublicNoiseWrapper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftedNoiseTask extends DensityCompilerTask<DensityFunctions.ShiftedNoise> {

    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String WRAPPER = Type.getInternalName(PublicNoiseWrapper.class);

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftedNoise node, DensityCompilerContext ctx) {


        PublicNoiseWrapper wrapper = new PublicNoiseWrapper(node.noise());
        ctx.emitLeafCallReference(mv, wrapper);

        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "blockX", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);

        ctx.compileNodeCompute(mv, node.shiftX());

        mv.visitInsn(DADD);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "blockY", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.yScale());
        mv.visitInsn(DMUL);

        ctx.compileNodeCompute(mv, node.shiftY());

        mv.visitInsn(DADD);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);

        ctx.compileNodeCompute(mv, node.shiftZ());

        mv.visitInsn(DADD);

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);
    }
}
