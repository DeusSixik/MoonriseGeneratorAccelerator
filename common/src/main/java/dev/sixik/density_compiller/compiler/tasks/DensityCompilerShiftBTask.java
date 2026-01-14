package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftBTask extends DensityCompilerTask<DensityFunctions.ShiftB> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";


    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftB node, PipelineAsmContext ctx) {
        DensityFunction.NoiseHolder holder = node.offsetNoise();
        ctx.visitCustomLeaf(holder, HOLDER_DESC);
        // wrapper.holder()

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockZ * 0.25

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockZ * 0.25, blockX * 0.25

        mv.visitInsn(DCONST_1);

        // (blockZ * 0.25, blockX * 0.25, 0)

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);

        // wrapper.holder().getValue(blockZ * 0.25, blockX * 0.25, 0)

        mv.visitLdcInsn(4.0D);
        mv.visitInsn(DMUL);

        // wrapper.holder().getValue(blockZ * 0.25, blockX * 0.25, 0) * 4.0
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.ShiftB node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            /*
                Generating the context for the current iteration
             */
            int tempCtx = ctx.newLocalInt();
            mv.visitVarInsn(ALOAD, 2); // Provider
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
            mv.visitVarInsn(ASTORE, tempCtx);

            /*
                Switch the context and call compute
             */
            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(tempCtx);
            this.compileCompute(mv, node, ctx);
            ctx.setCurrentContextVar(oldCtx);

            mv.visitInsn(DASTORE);
        });
    }
}
