package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static dev.sixik.density_compiler.utils.DensityCompilerUtils.*;

public class DensityCompilerTwoArgumentSimpleFunctionTask extends
        DensityCompilerTask<DensityFunctions.TwoArgumentSimpleFunction> {

    private static final Type MATH_TYPE = Type.getType(Math.class);
    private static final Method MATH_MIN = Method.getMethod("double min(double, double)");
    private static final Method MATH_MAX = Method.getMethod("double max(double, double)");

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.TwoArgumentSimpleFunction node, Step step) {
        if (step != Step.Compute) {
            ctx.readNode(node.argument1(), step);
            ctx.readNode(node.argument2(), step);
            return;
        }

        // 1. ПРОВЕРКА КЭША
        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        final GeneratorAdapter ga = ctx.mv();
        final DensityFunction arg1 = node.argument1();
        final DensityFunction arg2 = node.argument2();
        final var type = node.type();

        // 2. CONSTANT FOLDING (Не кэшируем в переменные)
        if (isConst(arg1) && isConst(arg2)) {
            double v1 = getConst(arg1);
            double v2 = getConst(arg2);
            double result = switch (type) {
                case ADD -> v1 + v2;
                case MUL -> v1 * v2;
                case MIN -> Math.min(v1, v2);
                case MAX -> Math.max(v1, v2);
            };
            ga.push(result);
            return;
        }

        // 3. IDENTITY & RANGE PRUNING
        // Если мы возвращаем результат другой ноды, кэшировать текущую (node)
        // нет смысла — мы просто пробрасываем кэш аргумента.
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            if (isConst(arg1, 0.0)) { ctx.readNode(arg2, Step.Compute); return; }
            if (isConst(arg2, 0.0)) { ctx.readNode(arg1, Step.Compute); return; }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            if (isConst(arg1, 0.0) || isConst(arg2, 0.0)) { ga.push(0.0); return; }
            if (isConst(arg1, 1.0)) { ctx.readNode(arg2, Step.Compute); return; }
            if (isConst(arg2, 1.0)) { ctx.readNode(arg1, Step.Compute); return; }

            if (arg1 == arg2 || arg1.equals(arg2)) {
                ctx.readNode(arg1, Step.Compute);
                ga.dup2();
                ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
                // Тут можно закэшировать, если x*x встречается часто
                saveToCache(ctx, node);
                return;
            }
        }

        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MAX) {
            if (arg1.minValue() >= arg2.maxValue()) { ctx.readNode(arg1, Step.Compute); return; }
            if (arg2.minValue() >= arg1.maxValue()) { ctx.readNode(arg2, Step.Compute); return; }
        }
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
            if (arg1.maxValue() <= arg2.minValue()) { ctx.readNode(arg1, Step.Compute); return; }
            if (arg2.maxValue() <= arg1.minValue()) { ctx.readNode(arg2, Step.Compute); return; }
        }

        // 4. СТАНДАРТНАЯ ГЕНЕРАЦИЯ
        ctx.readNode(arg1, Step.Compute);
        ctx.readNode(arg2, Step.Compute);

        switch (type) {
            case ADD -> ga.math(GeneratorAdapter.ADD, Type.DOUBLE_TYPE);
            case MUL -> ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
            case MIN -> ga.invokeStatic(Type.getType(Math.class), new Method("min", "(DD)D"));
            case MAX -> ga.invokeStatic(Type.getType(Math.class), new Method("max", "(DD)D"));
        }

        // 5. СОХРАНЕНИЕ РЕЗУЛЬТАТА
        saveToCache(ctx, node);
    }

    private void saveToCache(DCAsmContext ctx, DensityFunction node) {
        int id = ctx.mv().newLocal(Type.DOUBLE_TYPE);
        ctx.mv().dup2();
        ctx.mv().storeLocal(id);
        ctx.setVariable(node, id);
    }
}
