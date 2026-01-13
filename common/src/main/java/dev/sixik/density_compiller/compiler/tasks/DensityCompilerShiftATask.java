package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.PublicNoiseWrapper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftATask extends DensityCompilerTask<DensityFunctions.ShiftA> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String WRAPPER = Type.getInternalName(PublicNoiseWrapper.class);


    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftA node, DensityCompilerContext ctx) {
        final PublicNoiseWrapper wrapper = new PublicNoiseWrapper(node.offsetNoise());
        ctx.emitLeafCallReference(mv, wrapper);
        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        // wrapper.holder()

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockX * 0.25

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockX * 0.25

        mv.visitInsn(DCONST_0);

        // (blockX * 0.25, 0

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);
        // (blockX * 0.25, 0, blockZ * 0.25)

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);

        // wrapper.holder().getValue(blockX * 0.25, 0, blockZ * 0.25)

        mv.visitLdcInsn(4.0D);
        mv.visitInsn(DMUL);

        // wrapper.holder().getValue(blockX * 0.25, 0, blockZ * 0.25) * 4.0
    }
}
