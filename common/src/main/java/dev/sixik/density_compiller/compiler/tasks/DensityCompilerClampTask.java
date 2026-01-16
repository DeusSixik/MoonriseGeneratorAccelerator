package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerClampTask extends DensityCompilerTask<DensityFunctions.Clamp> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.Clamp node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.Clamp node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Clamp node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        DensityFunction input = node.input();
        double cMin = node.minValue(); // Границы клэмпа
        double cMax = node.maxValue();

        double inMin = input.minValue(); // Границы входной функции
        double inMax = input.maxValue();

        // 1. Оптимизация: Константный результат
        // Если вход всегда меньше минимума -> результат всегда min
        if (inMax <= cMin) {
            mv.visitLdcInsn(cMin);
            return;
        }
        // Если вход всегда больше максимума -> результат всегда max
        if (inMin >= cMax) {
            mv.visitLdcInsn(cMax);
            return;
        }

        // 2. Оптимизация: Clamp не нужен (вход уже внутри границ)
        if (inMin >= cMin && inMax <= cMax) {

            machine.pushStack(node.getClass(), input.getClass());
            ctx.visitNodeCompute(input);
            machine.popStack();
            return;
        }

        // 3. Частичный или полный Clamp
        machine.pushStack(node.getClass(), input.getClass());
        ctx.visitNodeCompute(input);
        machine.popStack();

        // Нужно ли обрезать сверху? (input может быть > max)
        boolean needMaxCheck = inMax > cMax;
        // Нужно ли обрезать снизу? (input может быть < min)
        boolean needMinCheck = inMin < cMin;

        if (needMaxCheck) {
            mv.visitLdcInsn(cMax);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
        }

        if (needMinCheck) {
            mv.visitLdcInsn(cMin);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }
    }
}
