package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftTask extends DensityCompilerTask<DensityFunctions.Shift> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";


    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Shift node, PipelineAsmContext ctx) {

        DensityFunction.NoiseHolder holder = node.offsetNoise();
        ctx.visitCustomLeaf(holder, HOLDER_DESC);
        // wrapper.holder()

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockX * 0.25

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockX * 0.25

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockX * 0.25, blockY * 0.25

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);
        // (blockX * 0.25, blockY * 0.25, blockZ * 0.25)

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);

        // wrapper.holder().getValue(blockX * 0.25, blockY * 0.25, blockZ * 0.25)

        mv.visitLdcInsn(4.0D);
        mv.visitInsn(DMUL);

        // wrapper.holder().getValue(blockX * 0.25, blockY * 0.25, blockZ * 0.25) * 4.0
    }
}
