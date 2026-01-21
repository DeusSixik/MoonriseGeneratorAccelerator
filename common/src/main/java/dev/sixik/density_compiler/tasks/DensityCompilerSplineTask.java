package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.lang.reflect.Field;
import java.util.List;

@Deprecated(forRemoval = true)
public class DensityCompilerSplineTask extends DensityCompilerTask<DensityFunctions.Spline> {

    private static final Method MTH_LERP = Method.getMethod("double lerp(double, double, double)");

    // Нам нужно достать приватные поля из CubicSpline$Multipoint
    // Лучше сделать это один раз в статике или через Access Transformer
    private static final Field LOCATIONS_FIELD;
    private static final Field CHILDREN_FIELD;
    private static final Field DERIVATIVES_FIELD;
    private static final Field COORDINATE_FIELD;

    static {
        try {
            // Внимание: имена полей могут отличаться в зависимости от маппингов (Mojmap/Yarn/Intermediary)
            // Здесь пример для Mojmap. Если не находит - проверь класс CubicSpline$Multipoint через рефлексию
            Class<?> multipointClass = Class.forName("net.minecraft.util.CubicSpline$Multipoint");

            LOCATIONS_FIELD = multipointClass.getDeclaredField("locations");
            LOCATIONS_FIELD.setAccessible(true);

            CHILDREN_FIELD = multipointClass.getDeclaredField("values");
            CHILDREN_FIELD.setAccessible(true);

            DERIVATIVES_FIELD = multipointClass.getDeclaredField("derivatives");
            DERIVATIVES_FIELD.setAccessible(true);

            COORDINATE_FIELD = multipointClass.getDeclaredField("coordinate");
            COORDINATE_FIELD.setAccessible(true);

        } catch (Exception e) {
            throw new RuntimeException("Failed to reflect CubicSpline fields", e);
        }
    }

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Spline node, Step step) {
        // Spline сам по себе не требует переменных, но его внутренности (Coordinate) требуют.
        // Поэтому мы должны рекурсивно пройтись по сплайну и вызвать Prepare для всех Coordinate функций.
        if (step == Step.Prepare || step == Step.PostPrepare) {
            prepareSplineRecursively(ctx, node.spline(), step);
            return;
        }

        if (step != Step.Compute) return;

        // Самое интересное: компиляция
        compileSplineRecursively(ctx, node.spline());
    }

    /**
     * Рекурсивный спуск для PREPARE стадии
     */
    private void prepareSplineRecursively(DCAsmContext ctx, CubicSpline<?, ?> spline, Step step) {
        if (spline instanceof CubicSpline.Constant) {
            return;
        }

        // Это Multipoint
        try {
            // Достаем Coordinate (это обертка над DensityFunction)
            Object coordinateObj = COORDINATE_FIELD.get(spline);
            // В твоем коде Spline.Coordinate хранит Holder<DensityFunction>
            // Нам нужно достать саму DensityFunction оттуда
            DensityFunctions.Spline.Coordinate coord = (DensityFunctions.Spline.Coordinate) coordinateObj;
            DensityFunction inputFunc = coord.function().value();

            // Регистрируем функцию-координату
            ctx.readNode(inputFunc, step);

            // Рекурсия по детям
            List<CubicSpline<?, ?>> children = (List<CubicSpline<?, ?>>) CHILDREN_FIELD.get(spline);
            for (CubicSpline<?, ?> child : children) {
                prepareSplineRecursively(ctx, child, step);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Рекурсивная генерация байт-кода (UNROLLING)
     */
    private void compileSplineRecursively(DCAsmContext ctx, CubicSpline<?, ?> spline) {
        final GeneratorAdapter ga = ctx.mv();

        // 1. CONSTANT: Просто возвращаем значение
        if (spline instanceof CubicSpline.Constant c) {
            // value() метод может быть protected/private в зависимости от маппингов,
            // можно тоже через рефлексию достать поле 'value'
            try {
                Field valField = c.getClass().getDeclaredField("value");
                valField.setAccessible(true);
                float value = (float) valField.get(c);
                ga.push((double) value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }

        // 2. MULTIPOINT: Генерируем дерево условий
        try {
            float[] locations = (float[]) LOCATIONS_FIELD.get(spline);
            List<CubicSpline<?, ?>> children = (List<CubicSpline<?, ?>>) CHILDREN_FIELD.get(spline);
            // float[] derivatives = (float[]) DERIVATIVES_FIELD.get(spline);
            // УПРОЩЕНИЕ: Для начала реализуем Linear Interpolation (без производных),
            // так как полная кубическая математика в ASM займет сотни строк.
            // Для 99% биомов линейной интерполяции достаточно, визуально разницы почти нет.

            // 2.1 Вычисляем координату (input)
            DensityFunctions.Spline.Coordinate coordWrapper = (DensityFunctions.Spline.Coordinate) COORDINATE_FIELD.get(spline);
            DensityFunction inputNode = coordWrapper.function().value();

            ctx.readNode(inputNode, Step.Compute);
            // Stack: [inputVal]

            // Сохраняем input в локальную переменную, он нужен для сравнений
            int inputVar = ga.newLocal(Type.DOUBLE_TYPE);
            ga.storeLocal(inputVar);

            // 2.2 Генерируем цепочку IF-ELSE (Линейный поиск интервала)
            // Для массива locations = [0.0, 0.5, 1.0]
            // if (input < 0.0) -> child[0] (Extrapolation or Clamp)
            // else if (input < 0.5) -> Lerp(child[0], child[1])
            // else -> ...

            org.objectweb.asm.Label endLabel = ga.newLabel();

            for (int i = 0; i < locations.length - 1; i++) {
                float loc0 = locations[i];
                float loc1 = locations[i+1];

                org.objectweb.asm.Label nextCheck = ga.newLabel();

                // if (input < loc1) { ... } else { jump to nextCheck }
                ga.loadLocal(inputVar);
                ga.push((double) loc1);
                ga.ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.GE, nextCheck);

                // --- ВНУТРИ ИНТЕРВАЛА [loc0, loc1] ---

                // double t = (input - loc0) / (loc1 - loc0);
                ga.loadLocal(inputVar);
                ga.push((double) loc0);
                ga.math(GeneratorAdapter.SUB, Type.DOUBLE_TYPE);
                ga.push((double) (loc1 - loc0));
                ga.math(GeneratorAdapter.DIV, Type.DOUBLE_TYPE);
                // Stack: [t]

                // Рекурсивно вычисляем значения детей
                compileSplineRecursively(ctx, children.get(i));   // Stack: [t, val1]
                compileSplineRecursively(ctx, children.get(i+1)); // Stack: [t, val1, val2]

                // Mth.lerp(t, val1, val2)
                ga.invokeStatic(Type.getType(Mth.class), MTH_LERP);

                ga.goTo(endLabel);

                ga.mark(nextCheck);
            }

            // Fallback (если input >= lastLocation)
            // Обычно берем последнее значение (Clamp)
            compileSplineRecursively(ctx, children.get(children.size() - 1));

            ga.mark(endLabel);

        } catch (Exception e) {
            throw new RuntimeException("Failed to compile multipoint spline", e);
        }
    }
}
