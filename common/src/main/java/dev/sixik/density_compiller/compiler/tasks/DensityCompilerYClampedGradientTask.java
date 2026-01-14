package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static dev.sixik.density_compiller.compiler.DensityCompiler.CTX;
import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerYClampedGradientTask extends DensityCompilerTask<DensityFunctions.YClampedGradient> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {
        // 1. blockY (берем из контекста)
        ctx.loadContext();
//        ctx.invokeContextInterface("blockY", "()I");
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
        mv.visitInsn(I2D);

        // 2. Параметры (сразу как double, чтобы избежать I2D в байт-коде)
        mv.visitLdcInsn((double) node.fromY());
        mv.visitLdcInsn((double) node.toY());
        mv.visitLdcInsn(node.fromValue());
        mv.visitLdcInsn(node.toValue());

        // 3. Вызов Mth.clampedMap
        DensityCompilerUtils.clampedMap(mv);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx, int destArrayVar) {
        // Используем цикл с ленивым контекстом
        ctx.arrayForI(destArrayVar, (iVar) -> {
            ctx.startLoop(); // Сброс кэша контекста для новой итерации

            // Готовим стек для записи: ds[i] = ...
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            // 1. Получаем контекст для текущего i
            int loopCtx = ctx.getOrAllocateLoopContext(iVar);

            // 2. Достаем blockY из этого контекста
            mv.visitVarInsn(ALOAD, loopCtx);
//            ctx.invokeContextInterface("blockY", "()I");
            mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
            mv.visitInsn(I2D);

            // 3. Загружаем константы (сразу double)
            mv.visitLdcInsn((double) node.fromY());
            mv.visitLdcInsn((double) node.toY());
            mv.visitLdcInsn(node.fromValue());
            mv.visitLdcInsn(node.toValue());

            // 4. Считаем map
            DensityCompilerUtils.clampedMap(mv);

            // 5. Сохраняем
            mv.visitInsn(DASTORE);
        });
    }
}
