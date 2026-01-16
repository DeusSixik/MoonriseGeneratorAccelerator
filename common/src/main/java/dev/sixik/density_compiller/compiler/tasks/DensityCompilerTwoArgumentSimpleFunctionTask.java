package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerTwoArgumentSimpleFunctionTask extends
        DensityCompilerTask<DensityFunctions.TwoArgumentSimpleFunction> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.TwoArgumentSimpleFunction node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.argument1().getClass());
        ctx.visitNodeCompute(node.argument1(), PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.argument2().getClass());
        ctx.visitNodeCompute(node.argument2(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.TwoArgumentSimpleFunction node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.argument1().getClass());
        ctx.visitNodeCompute(node.argument1(), POST_PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.argument2().getClass());
        ctx.visitNodeCompute(node.argument2(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv,
                                  DensityFunctions.TwoArgumentSimpleFunction node,
                                  PipelineAsmContext ctx
    ) {
        var machine = ctx.pipeline().stackMachine();

        DensityFunction arg1 = node.argument1();
        DensityFunction arg2 = node.argument2();
        var type = node.type();

        // 1. Если оба аргумента константы — считаем на этапе компиляции
        if (isConst(arg1) && isConst(arg2)) {
            double v1 = getConst(arg1);
            double v2 = getConst(arg2);
            double result = switch (type) {
                case ADD -> v1 + v2;
                case MUL -> v1 * v2;
                case MIN -> Math.min(v1, v2);
                case MAX -> Math.max(v1, v2);
            };
            mv.visitLdcInsn(result);
            return;
        }

        // 2. Оптимизации для ADD
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            // x + 0 = x
            if (isConst(arg1, 0.0)) {
                machine.pushStack(node.getClass(), arg2.getClass());
                ctx.visitNodeCompute(arg2);
                machine.popStack();
                return;
            }
            if (isConst(arg2, 0.0)) {
                machine.pushStack(node.getClass(), arg1.getClass());
                ctx.visitNodeCompute(arg1);
                machine.popStack();
                return;
            }
        }

        // 3. Оптимизации для MUL
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            // x * 0 = 0
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) {
                mv.visitInsn(DCONST_0);
                return;
            }
            // x * 1 = x
            if (isConst(arg1, 1.0)) {
                machine.pushStack(node.getClass(), arg2.getClass());
                ctx.visitNodeCompute(arg2);
                machine.popStack();
                return;
            }
            if (isConst(arg2, 1.0)) {
                machine.pushStack(node.getClass(), arg1.getClass());
                ctx.visitNodeCompute(arg1);
                machine.popStack();
                return;
            }
        }

        // Стандартная генерация
        machine.pushStack(node.getClass(), arg1.getClass());
        ctx.visitNodeCompute(arg1);
        machine.popStack();
        machine.pushStack(node.getClass(), arg2.getClass());
        ctx.visitNodeCompute(arg2);
        machine.popStack();
        switch (type) {
            case ADD -> mv.visitInsn(DADD);
            case MUL -> mv.visitInsn(DMUL);
            case MIN -> DensityCompilerUtils.min(mv);
            case MAX -> DensityCompilerUtils.max(mv);
        }
    }

//    @Override
//    public void compileFill(MethodVisitor mv, DensityFunctions.TwoArgumentSimpleFunction node, PipelineAsmContext ctx, int destArrayVar) {
//        DensityFunction arg1 = node.argument1();
//        DensityFunction arg2 = node.argument2();
//        var type = node.type();
//
//        // --- Оптимизация 1: Identity (Ничего не делать) ---
//        // x + 0 или x * 1 -> просто заливаем x
//        if ((type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD && (isConst(arg1, 0.0) || isConst(arg2, 0.0))) ||
//                (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL && (isConst(arg1, 1.0) || isConst(arg2, 1.0)))) {
//
//            DensityFunction nonConstArg = isConst(arg1) ? arg2 : arg1;
//            ctx.visitNodeFill(nonConstArg, destArrayVar);
//            return;
//        }
//
//        // --- Оптимизация 2: Scalar + Vector (Без аллокации буфера) ---
//        // Если один аргумент константа, а второй сложный -> заливаем сложный, потом циклом применяем константу
//        // Для MUL, ADD, MIN, MAX порядок аргументов не важен (коммутативность)
//        if (isConst(arg1) || isConst(arg2)) {
//            double constantValue = isConst(arg1) ? getConst(arg1) : getConst(arg2);
//            DensityFunction vectorArg = isConst(arg1) ? arg2 : arg1;
//
//            // 1. Заливаем векторную часть в dest
//            ctx.visitNodeFill(vectorArg, destArrayVar);
//
//            // 2. Проходим по массиву и применяем операцию с константой
//            ctx.arrayForI(destArrayVar, (iVar) -> {
//                mv.visitVarInsn(ALOAD, destArrayVar); // array ref
//                mv.visitVarInsn(ILOAD, iVar);         // index
//                mv.visitInsn(DUP2);                   // [array, index, array, index]
//                mv.visitInsn(DALOAD);                 // [array, index, value]
//
//                mv.visitLdcInsn(constantValue);       // [array, index, value, const]
//
//                switch (type) {
//                    case ADD -> mv.visitInsn(DADD);
//                    case MUL -> mv.visitInsn(DMUL);
//                    case MIN -> DensityCompilerUtils.min(mv);
//                    case MAX -> DensityCompilerUtils.max(mv);
//                }
//
//                mv.visitInsn(DASTORE);                // Store back
//            });
//            return;
//        }
//
//        // --- Стандартная логика (Vector + Vector) ---
//        // Если оба аргумента сложные, придется использовать временный буфер (или ленивый MUL)
//
//        ctx.visitNodeFill(arg1, destArrayVar);
//
//        // Ленивый MUL (как было у тебя, это хорошая оптимизация)
//        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
//            ctx.arrayForI(destArrayVar, (iVar) -> {
//                Label skip = new Label();
//                mv.visitVarInsn(ALOAD, destArrayVar);
//                mv.visitVarInsn(ILOAD, iVar);
//                mv.visitInsn(DALOAD);
//                mv.visitInsn(DUP2);
//                mv.visitInsn(DCONST_0);
//                mv.visitInsn(DCMPL);
//                mv.visitJumpInsn(IFEQ, skip);
//
//                ctx.compileNodeComputeForIndex(mv, arg2, iVar);
//                mv.visitInsn(DMUL);
//
//                mv.visitVarInsn(ALOAD, destArrayVar);
//                mv.visitVarInsn(ILOAD, iVar);
//                mv.visitInsn(DUP2_X2);
//                mv.visitInsn(POP2);
//                mv.visitInsn(DASTORE);
//
//                mv.visitLabel(skip);
//                mv.visitInsn(POP2);
//            });
//            return;
//        }
//
//        // ADD, MIN, MAX для двух векторов
//        int tempArrayVar = ctx.allocateTempBuffer();
//        ctx.visitNodeFill(arg2, tempArrayVar);
//
//        int opcode = switch (type) {
//            case ADD -> DADD;
//            default -> -1;
//        };
//
//        ctx.arrayForI(destArrayVar, (iVar) -> {
//            mv.visitVarInsn(ALOAD, destArrayVar);
//            mv.visitVarInsn(ILOAD, iVar);
//            mv.visitInsn(DUP2);
//            mv.visitInsn(DALOAD);
//
//            mv.visitVarInsn(ALOAD, tempArrayVar);
//            mv.visitVarInsn(ILOAD, iVar);
//            mv.visitInsn(DALOAD);
//
//            if (opcode != -1) {
//                mv.visitInsn(opcode);
//            } else if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
//                DensityCompilerUtils.min(mv);
//            } else {
//                DensityCompilerUtils.max(mv);
//            }
//
//            mv.visitInsn(DASTORE);
//        });
//    }

    // --- Helpers ---

    private boolean isConst(DensityFunction f) {
        return f instanceof DensityFunctions.Constant;
    }

    private boolean isConst(DensityFunction f, double val) {
        return f instanceof DensityFunctions.Constant c && c.value() == val;
    }

    private double getConst(DensityFunction f) {
        return ((DensityFunctions.Constant)f).value();
    }
}
