package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.PublicNoiseWrapper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerNoiseTask extends DensityCompilerTask<DensityFunctions.Noise> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String WRAPPER = Type.getInternalName(PublicNoiseWrapper.class);


    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Noise node, DensityCompilerContext ctx) {
        final PublicNoiseWrapper wrapper = new PublicNoiseWrapper(node.noise());
        ctx.emitLeafCallReference(mv, wrapper);
        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        // wrapper.holder()

        ctx.loadContext(mv);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);

        // (blockX * 0.25

        ctx.loadContext(mv);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.yScale());
        mv.visitInsn(DMUL);

        // (blockX * xzScale, blockY * yScale

        ctx.loadContext(mv);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);
        // (blockX * xzScale, blockY * yScale, blockZ * xzScale)

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);

        // wrapper.holder().getValue(blockX * xzScale, blockY * yScale, blockZ * xzScale)
    }
}
