package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

public class DensityCompilerRangeChoiceTask extends DensityCompilerTask<DensityFunctions.RangeChoice> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.RangeChoice node, Step step) {
        if (step != Step.Compute) {
            ctx.readNode(node.input(), step);
            ctx.readNode(node.whenInRange(), step);
            ctx.readNode(node.whenOutOfRange(), step);
            return;
        }

        // 1. Проверка кеша
        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        double min = node.minInclusive();
        double max = node.maxExclusive();
        double inMin = node.input().minValue();
        double inMax = node.input().maxValue();

        // 2. Статический прунинг
        if (inMin >= min && inMax < max) {
            ctx.readNode(node.whenInRange(), Step.Compute);
            return;
        }
        if (inMax < min || inMin >= max) {
            ctx.readNode(node.whenOutOfRange(), Step.Compute);
            return;
        }

        // 3. Динамическая логика
        final GeneratorAdapter ga = ctx.mv();
        Label runOutOfRange = ga.newLabel();
        Label end = ga.newLabel();

        // Вычисляем input один раз и сохраняем
        ctx.readNode(node.input(), Step.Compute);
        int inputVar = ga.newLocal(Type.DOUBLE_TYPE);
        ga.storeLocal(inputVar);

        // Условие: (input < min || input >= max) -> jump to OutOfRange
        ga.loadLocal(inputVar);
        ga.push(min);
        ga.ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.LT, runOutOfRange);

        ga.loadLocal(inputVar);
        ga.push(max);
        ga.ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.GE, runOutOfRange);

        // --- Ветка IN_RANGE ---
        // Используем scope, чтобы переменные внутри ветки не "утекали" наружу
        ctx.scope(() -> {
            ctx.readNode(node.whenInRange(), Step.Compute);
        });
        ga.goTo(end);

        // --- Ветка OUT_OF_RANGE ---
        ga.mark(runOutOfRange);
        ctx.scope(() -> {
            ctx.readNode(node.whenOutOfRange(), Step.Compute);
        });

        ga.mark(end);

        // 4. Кешируем результат RangeChoice
        int id = ga.newLocal(Type.DOUBLE_TYPE);
        ga.dup2();
        ga.storeLocal(id);
        ctx.setVariable(node, id);
    }
}
