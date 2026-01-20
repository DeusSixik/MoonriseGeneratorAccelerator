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

        if(step != Step.Compute) {
            ctx.readNode(node.input(), step);
            ctx.readNode(node.whenInRange(), step);
            ctx.readNode(node.whenOutOfRange(), step);
            return;
        }

        double min = node.minInclusive();
        double max = node.maxExclusive();
        double inMin = node.input().minValue();
        double inMax = node.input().maxValue();

        // 2. Статический прунинг (Dead Code Elimination)
        if (inMin >= min && inMax < max) {
            ctx.readNode(node.whenInRange(), Step.Compute);
            return;
        }
        if (inMax < min || inMin >= max) {
            ctx.readNode(node.whenOutOfRange(), Step.Compute);
            return;
        }

        // 3. Динамическая логика через GeneratorAdapter
        final GeneratorAdapter ga = ctx.mv();
        Label runOutOfRange = ga.newLabel();
        Label end = ga.newLabel();

        // Вычисляем input и сохраняем в локальную переменную
        ctx.readNode(node.input(), Step.Compute);
        int inputVar = ga.newLocal(Type.DOUBLE_TYPE);
        ga.storeLocal(inputVar);

        // Проверка: input < min
        ga.loadLocal(inputVar);
        ga.push(min);
        ga.ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.LT, runOutOfRange);

        // Проверка: input >= max
        ga.loadLocal(inputVar);
        ga.push(max);
        ga.ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.GE, runOutOfRange);

        // --- Ветка IN_RANGE ---
        ctx.readNode(node.whenInRange(), Step.Compute);
        ga.goTo(end);

        // --- Ветка OUT_OF_RANGE ---
        ga.mark(runOutOfRange);
        ctx.readNode(node.whenOutOfRange(), Step.Compute);

        ga.mark(end);
    }
}
