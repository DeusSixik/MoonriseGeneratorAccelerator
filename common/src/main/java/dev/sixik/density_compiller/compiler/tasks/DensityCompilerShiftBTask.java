package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.PublicNoiseWrapper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftBTask extends DensityCompilerTask<DensityFunctions.ShiftB> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String WRAPPER = Type.getInternalName(PublicNoiseWrapper.class);


    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftB node, DensityCompilerContext ctx) {
        final PublicNoiseWrapper wrapper = new PublicNoiseWrapper(node.offsetNoise());
        ctx.emitLeafCallReference(mv, wrapper);
        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        // wrapper.holder()

        ctx.loadContext(mv);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);

        // (blockZ * 0.25

        ctx.loadContext(mv);
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
    public void compileFill(MethodVisitor mv, DensityFunctions.ShiftB node, DensityCompilerContext ctx, int destArrayVar) {
        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            /*
                Generating the context for the current iteration
             */
            int tempCtx = ctx.allocateLocalVarIndex();
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
