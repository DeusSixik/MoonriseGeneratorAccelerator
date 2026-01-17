package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerRangeChoiceTask extends DensityCompilerTask<DensityFunctions.RangeChoice> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        // Подготовка стековой машины (без изменений, всё верно)
        var machine = ctx.pipeline().stackMachine();
        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        machine.popStack();
        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenInRange(), PREPARE_COMPUTE);
        machine.popStack();
        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenOutOfRange(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        // (без изменений)
        var machine = ctx.pipeline().stackMachine();
        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        machine.popStack();
        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenInRange(), POST_PREPARE_COMPUTE);
        machine.popStack();
        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenOutOfRange(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        double min = node.minInclusive();
        double max = node.maxExclusive();

        // Используем min/max value для статического прунинга (Dead Code Elimination)
        double inMin = node.input().minValue();
        double inMax = node.input().maxValue();

        // 1. Оптимизация: Всегда внутри диапазона
        if (inMin >= min && inMax < max) {
            machine.pushStack(node.getClass(), node.whenInRange().getClass());
            ctx.visitNodeCompute(node.whenInRange());
            machine.popStack();
            return;
        }

        // 2. Оптимизация: Всегда вне диапазона
        // (В Minecraft RangeChoice - это [min, max), то есть max эксклюзивен)
        if (inMax < min || inMin >= max) {
            machine.pushStack(node.getClass(), node.whenOutOfRange().getClass());
            ctx.visitNodeCompute(node.whenOutOfRange());
            machine.popStack();
            return;
        }

        ctx.comment("RangeChoice: " + min + " <= x < " + max);

        // 3. Динамическая проверка
        // Генерируем input
        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input());
        machine.popStack();

        // Сохраняем input во временную переменную, чтобы сравнивать его дважды
        // (Мы не пишем результат в эту переменную, только читаем!)
        int inputVar = ctx.newLocalDouble();
        mv.visitVarInsn(DSTORE, inputVar);

        Label runOutOfRange = new Label();
        Label end = new Label();

        // Check 1: input < min ? GOTO OutOfRange
        mv.visitVarInsn(DLOAD, inputVar);
        mv.visitLdcInsn(min);
        mv.visitInsn(DCMPG); // Compare returns -1, 0, 1
        mv.visitJumpInsn(IFLT, runOutOfRange); // Если input < min

        // Check 2: input >= max ? GOTO OutOfRange
        mv.visitVarInsn(DLOAD, inputVar);
        mv.visitLdcInsn(max);
        mv.visitInsn(DCMPG);
        mv.visitJumpInsn(IFGE, runOutOfRange); // Если input >= max (exclusive)

        // --- IN RANGE BRANCH ---
        // Если мы здесь, значит (input >= min && input < max)
        machine.pushStack(node.getClass(), node.whenInRange().getClass());
        ctx.visitNodeCompute(node.whenInRange());
        machine.popStack();
        mv.visitJumpInsn(GOTO, end); // Прыгаем в конец, результат на стеке

        // --- OUT OF RANGE BRANCH ---
        mv.visitLabel(runOutOfRange);
        machine.pushStack(node.getClass(), node.whenOutOfRange().getClass());
        ctx.visitNodeCompute(node.whenOutOfRange());
        machine.popStack();

        // --- END ---
        mv.visitLabel(end);

        // В конце на стеке лежит либо результат InRange, либо результат OutOfRange.
        // Переменная inputVar больше не нужна.
    }
}