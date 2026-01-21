package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler.*;

public abstract class DensityCompilerShiftTaskBase<T extends DensityFunction> extends DensityCompilerTask<T> {

    protected static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final Type NOISE_HOLDER_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;");
    private static final Method GET_VALUE = Method.getMethod("double getValue(double, double, double)");

    protected abstract void generateCoordinates(GeneratorAdapter ga, DCAsmContext ctx);
    protected abstract DensityFunction.NoiseHolder getHolder(T node);

    @Override
    protected void applyStep(DCAsmContext ctx, T node, Step step) {
        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_X_BITS | BLOCK_Y_BITS | BLOCK_Z_BITS);
            return;
        }

        if (step != Step.Compute) return;

        // 1. Проверка кеша (чтобы не считать шум дважды)
        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        final GeneratorAdapter ga = ctx.mv();
        final DensityFunction.NoiseHolder holder = getHolder(node);

        // 2. Вычисление
        ctx.readLeaf(holder, HOLDER_DESC);
        generateCoordinates(ga, ctx);
        ga.invokeVirtual(NOISE_HOLDER_TYPE, GET_VALUE);

        // Масштабирование (специфика Shift-функций в MC)
        if (shouldScale()) {
            ga.push(4.0D);
            ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
        }

        // 3. Сохранение и дублирование на стеке
        int id = ga.newLocal(Type.DOUBLE_TYPE);
        ga.dup2();
        ga.storeLocal(id);
        ctx.setVariable(node, id);
    }

    /**
     * Генерирует (coordinate * 0.25)
     * @param blockKey Константы типа DensityFunctionsCacheHandler.BLOCK_X
     */
    protected void genCoord(GeneratorAdapter ga, DCAsmContext ctx, String blockKey) {
        int var = ctx.getCachedVariable(blockKey);
        if (var == -1) {
            throw new IllegalStateException("Variable " + blockKey + " not loaded!");
        }

        // Загружаем int, приводим к double и умножаем на 0.25
        ga.loadLocal(var);
        ga.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);
        ga.push(0.25D);
        ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
    }

    protected boolean shouldScale() {
        return true;
    }
}
