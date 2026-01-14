package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMulOrAddTask extends DensityCompilerTask<DensityFunctions.MulOrAdd> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.MulOrAdd node, PipelineAsmContext ctx) {
        double arg = node.argument();
        var type = node.specificType();

        // 1. Оптимизация: Умножение на 0 (Результат всегда 0)
        if (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 0.0) {
            mv.visitInsn(DCONST_0);
            return;
        }

        // 2. Оптимизация: Identity (+0 или *1 -> просто вычисляем input)
        if ((type == DensityFunctions.MulOrAdd.Type.ADD && arg == 0.0) ||
                (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 1.0)) {
            ctx.visitNodeCompute(node.input());
            return;
        }

        // Стандартная логика
        ctx.visitNodeCompute(node.input());
        mv.visitLdcInsn(arg);

        if (type == DensityFunctions.MulOrAdd.Type.MUL) {
            mv.visitInsn(DMUL);
        } else {
            mv.visitInsn(DADD);
        }
    }

//    @Override
//    public void compileFill(MethodVisitor mv, DensityFunctions.MulOrAdd node, PipelineAsmContext ctx, int destArrayVar) {
//        double arg = node.argument();
//        var type = node.specificType();
//
//        // --- Оптимизация 1: Умножение на 0 (Nuclear Option) ---
//        // Самое важное: НЕ вычисляем input вообще! Просто заливаем нулями.
//        if (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 0.0) {
//            ctx.visitNodeFill(new DensityFunctions.Constant(0.0), destArrayVar);
//            return;
//        }
//
//        // --- Оптимизация 2: Identity ---
//        // Если +0 или *1, просто вычисляем input в массив и уходим.
//        // Никаких дополнительных циклов.
//        if ((type == DensityFunctions.MulOrAdd.Type.ADD && arg == 0.0) ||
//                (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 1.0)) {
//            ctx.visitNodeFill(node.input(), destArrayVar);
//            return;
//        }
//
//        // --- Стандартная логика (Scalar application) ---
//
//        // 1. Сначала считаем тяжелый input
//        ctx.visitNodeFill(node.input(), destArrayVar);
//
//        // 2. Применяем скаляр
//        int opcode = (type == DensityFunctions.MulOrAdd.Type.MUL) ? DMUL : DADD;
//
//        ctx.arrayForI(destArrayVar, (iVar) -> {
//            mv.visitVarInsn(ALOAD, destArrayVar);
//            mv.visitVarInsn(ILOAD, iVar);
//            mv.visitInsn(DUP2);   // [Arr, I, Arr, I]
//
//            mv.visitInsn(DALOAD); // [Arr, I, Val]
//            mv.visitLdcInsn(arg); // [Arr, I, Val, Arg]
//            mv.visitInsn(opcode); // [Arr, I, NewVal]
//
//            mv.visitInsn(DASTORE);
//        });
//    }
}
