package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerYClampedGradientTask extends DensityCompilerTask<DensityFunctions.YClampedGradient> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {
        // (double) functionContext.blockY()
//        ctx.loadContext();

        ctx.loadBlockY();
//        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
//        mv.visitInsn(I2D);

        // Параметры градиента
        mv.visitLdcInsn((double) node.fromY());
        mv.visitLdcInsn((double) node.toY());
        mv.visitLdcInsn(node.fromValue());
        mv.visitLdcInsn(node.toValue());

        // Используем инлайновую версию для скорости
        DensityCompilerUtils.clampedMap(mv);
    }

//    @Override
//    public void compileFill(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx, int destArrayVar) {
//        // Кешируем параметры градиента в локальные переменные перед циклом,
//        // чтобы не делать LDC 4 раза за итерацию (улучшает читаемость байт-кода и работу с регистрами)
//        int vFromY = ctx.newLocalDouble();
//        mv.visitLdcInsn((double) node.fromY());
//        mv.visitVarInsn(DSTORE, vFromY);
//
//        int vToY = ctx.newLocalDouble();
//        mv.visitLdcInsn((double) node.toY());
//        mv.visitVarInsn(DSTORE, vToY);
//
//        int vFromVal = ctx.newLocalDouble();
//        mv.visitLdcInsn(node.fromValue());
//        mv.visitVarInsn(DSTORE, vFromVal);
//
//        int vToVal = ctx.newLocalDouble();
//        mv.visitLdcInsn(node.toValue());
//        mv.visitVarInsn(DSTORE, vToVal);
//
//        ctx.arrayForI(destArrayVar, (iVar) -> {
//            // Стек для DASTORE
//            mv.visitVarInsn(ALOAD, destArrayVar);
//            mv.visitVarInsn(ILOAD, iVar);
//
//            // 1. Получаем Y
//            int loopCtx = ctx.getOrAllocateLoopContext(iVar);
//            mv.visitVarInsn(ALOAD, loopCtx);
//            mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
//            mv.visitInsn(I2D);
//
//            // 2. Загружаем параметры из локальных переменных
//            mv.visitVarInsn(DLOAD, vFromY);
//            mv.visitVarInsn(DLOAD, vToY);
//            mv.visitVarInsn(DLOAD, vFromVal);
//            mv.visitVarInsn(DLOAD, vToVal);
//
//            // 3. Вычисление
//            DensityCompilerUtils.clampedMap(mv);
//
//            mv.visitInsn(DASTORE);
//        });
//    }
}
