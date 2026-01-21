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
        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_Y_BITS);
            return;
        }

        if (step != Step.Compute) return;

        // 1. ПРОВЕРКА КЭША
        // Если в рамках текущей точки [x,y,z] мы уже считали этот градиент — берем из слота
        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        final GeneratorAdapter ga = ctx.mv();

        // --- ВЫЧИСЛЕНИЕ ---
        // 1. Загружаем blockY
        int yVarIndex = ctx.getCachedVariable(BLOCK_Y);
        ga.loadLocal(yVarIndex);
        ga.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);

        // 2. Загружаем параметры (константы)
        ga.push((double) node.fromY());
        ga.push((double) node.toY());
        ga.push(node.fromValue());
        ga.push(node.toValue());

        // 3. Вызов Mth.clampedMap(DDDDD)D
        ga.invokeStatic(MTH_TYPE, CLAMPED_MAP);

        // 4. СОХРАНЕНИЕ В КЭШ
        // На "горячем пути" это экономит 4 PUSH и 1 INVOKESTATIC при повторном обращении
        int id = ga.newLocal(Type.DOUBLE_TYPE);
        ga.dup2();
        ga.storeLocal(id);
        ctx.setVariable(node, id);
    }
}
