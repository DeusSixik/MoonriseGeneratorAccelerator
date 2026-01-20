package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler.BLOCK_Y;
import static dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler.BLOCK_Y_BITS;

public class DensityCompilerYClampedGradientTask extends DensityCompilerTask<DensityFunctions.YClampedGradient> {

    // Ссылка на Mth.clampedMap(double value, double minIn, double maxIn, double minOut, double maxOut)
    private static final Type MTH_TYPE = Type.getType(Mth.class);
    private static final Method CLAMPED_MAP = Method.getMethod("double clampedMap(double, double, double, double, double)");

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.YClampedGradient node, Step step) {
        // 1. Prepare: Запрашиваем переменную Y
        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_Y_BITS);
            return;
        }

        // 2. PostPrepare: Убираем кэширование результата!
        // Результат зависит от Y, который меняется в цикле. Мы не можем предрассчитать его здесь.

        if (step != Step.Compute) return;

        final GeneratorAdapter ga = ctx.mv();

        // --- ГЕНЕРАЦИЯ ВЫЧИСЛЕНИЯ ---
        // Формула: Mth.clampedMap(blockY, fromY, toY, fromValue, toValue)

        // 1. Загружаем blockY (double value)
        int yVarIndex = ctx.getCachedVariable(BLOCK_Y);
        ga.loadLocal(yVarIndex);
        ga.cast(Type.INT_TYPE, Type.DOUBLE_TYPE); // I2D

        // 2. Загружаем fromY (double minInput)
        ga.push((double) node.fromY());

        // 3. Загружаем toY (double maxInput)
        ga.push((double) node.toY());

        // 4. Загружаем fromValue (double minOutput)
        ga.push(node.fromValue());

        // 5. Загружаем toValue (double maxOutput)
        ga.push(node.toValue());

        // 6. Вызов Mth.clampedMap
        ga.invokeStatic(MTH_TYPE, CLAMPED_MAP);
    }
}
