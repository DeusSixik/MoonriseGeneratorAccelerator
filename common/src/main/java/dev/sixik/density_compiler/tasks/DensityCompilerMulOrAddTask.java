package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMulOrAddTask extends DensityCompilerTask<DensityFunctions.MulOrAdd> {
    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.MulOrAdd node, Step step) {
        if (step != Step.Compute) {
            ctx.readNode(node.input(), step);
            return;
        }

        // 1. Проверка кеша самой ноды
        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        final DensityFunction input = node.input();
        final double arg = node.argument();
        final DensityFunctions.MulOrAdd.Type type = node.specificType();
        final GeneratorAdapter ga = ctx.mv();

        // 2. Оптимизация: Constant Folding
        if (input instanceof DensityFunctions.Constant c) {
            double result = (type == DensityFunctions.MulOrAdd.Type.MUL) ? c.value() * arg : c.value() + arg;
            ctx.push(result);
            return;
        }

        // 3. Оптимизация: Умножение на 0
        if (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 0.0) {
            ctx.push(0.0);
            return;
        }

        // 4. Оптимизация: Identity (+0 или *1)
        if ((type == DensityFunctions.MulOrAdd.Type.ADD && arg == 0.0) ||
                (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 1.0)) {
            ctx.readNode(input, Step.Compute);
            // Не кешируем в локальную переменную, так как это просто проброс
            return;
        }

        // 5. Вычисление
        ctx.readNode(input, Step.Compute);
        ctx.push(arg);
        ga.math(type == DensityFunctions.MulOrAdd.Type.MUL ? GeneratorAdapter.MUL : GeneratorAdapter.ADD, Type.DOUBLE_TYPE);

        // 6. Кеширование результата (для повторного использования в графе)
        int id = ga.newLocal(Type.DOUBLE_TYPE);
        ga.dup2();
        ga.storeLocal(id);
        ctx.setVariable(node, id);
    }
}
