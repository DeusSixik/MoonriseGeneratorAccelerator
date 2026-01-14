package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftedNoiseTask extends DensityCompilerTask<DensityFunctions.ShiftedNoise> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftedNoise node, PipelineAsmContext ctx) {
        DensityFunction.NoiseHolder holder = node.noise();
        ctx.visitCustomLeaf(holder, HOLDER_DESC);
        ctx.loadContext();
//        ctx.invokeContextInterface("blockX", "()I");
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);

        ctx.visitNodeCompute(node.shiftX());

        mv.visitInsn(DADD);

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.yScale());
        mv.visitInsn(DMUL);

        ctx.visitNodeCompute(node.shiftY());

        mv.visitInsn(DADD);

        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);

        ctx.visitNodeCompute(node.shiftZ());

        mv.visitInsn(DADD);

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);
    }

//    @Override
//    public void compileFill(MethodVisitor mv, DensityFunctions.ShiftedNoise node, PipelineAsmContext ctx, int destArrayVar) {
//        ctx.arrayForI(destArrayVar, (iVar) -> {
//            mv.visitVarInsn(ALOAD, destArrayVar);
//            mv.visitVarInsn(ILOAD, iVar);
//
//            // Сбрасываем кэш контекста для новой итерации
//            ctx.startLoop();
//
//            // Получаем (или создаем) контекст
//            int currentCtx = ctx.getOrAllocateLoopContext(iVar);
//
//            int oldCtx = ctx.getCurrentContextVar();
//            ctx.setCurrentContextVar(currentCtx);
//
//            // Генерируем вычисления шума (все внутренние compileNodeCompute подхватят наш currentCtx)
//            this.compileCompute(mv, node, ctx);
//
//            ctx.setCurrentContextVar(oldCtx);
//
//            mv.visitInsn(DASTORE);
//        });
//    }
}
