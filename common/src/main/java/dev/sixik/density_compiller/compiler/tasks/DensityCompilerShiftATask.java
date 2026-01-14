package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftATask extends DensityCompilerTask<DensityFunctions.ShiftA> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";


    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftA node, PipelineAsmContext ctx) {
        DensityFunction.NoiseHolder holder = node.offsetNoise();
        ctx.visitCustomLeaf(holder, HOLDER_DESC);

//        ctx.visitLeafReference(wrapper);
//        mv.visitTypeInsn(CHECKCAST, WRAPPER);
//        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        // wrapper.holder()

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockX * 0.25

        mv.visitInsn(DCONST_0);

        // (blockX * 0.25, 0

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);
        // (blockX * 0.25, 0, blockZ * 0.25)

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER_INTERNAL, "getValue", "(DDD)D", false);
        // holder().getValue(blockX * 0.25, 0, blockZ * 0.25)

        mv.visitLdcInsn(4.0D);
        mv.visitInsn(DMUL);

        // holder().getValue(blockX * 0.25, 0, blockZ * 0.25) * 4.0
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.ShiftA node, PipelineAsmContext ctx, int destArrayVar) {

        /*
            ShiftNoise is the point where Minecraft usually stops vector optimization.
            We will expand this into a loop that does not create FunctionContext objects,
            but pulls blockX()/blockZ() directly.
         */
        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            /*
                We repeat the compute logic, but we take the context from the provider for each i
             */
            int tempCtx = ctx.newLocalInt();
            mv.visitVarInsn(ALOAD, 2); // Provider
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
            mv.visitVarInsn(ASTORE, tempCtx);

            /*
                Setting the current context for loadContext to work correctly in compileCompute
             */
            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(tempCtx);

            this.compileCompute(mv, node, ctx);

            ctx.setCurrentContextVar(oldCtx);

            mv.visitInsn(DASTORE);
        });
    }
}
