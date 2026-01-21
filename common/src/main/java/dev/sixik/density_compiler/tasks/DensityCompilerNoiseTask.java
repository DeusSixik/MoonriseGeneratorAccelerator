package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler.*;

public class DensityCompilerNoiseTask extends DensityCompilerTask<DensityFunctions.Noise> {

    private static final Type NOISE_HOLDER_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;");
    private static final Method GET_VALUE = Method.getMethod("double getValue(double, double, double)");
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";


    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Noise node, Step step) {
        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_X_BITS | BLOCK_Y_BITS | BLOCK_Z_BITS);
            return;
        }

        if (step != Step.Compute) return;

        int id = ctx.getVariable(node);
        if(id != -1) {
            ctx.mv().loadLocal(id);
        } else {
            final GeneratorAdapter ga = ctx.mv();

// 1. Сначала вычисляем значение, чтобы оно лежало на стеке
            ctx.readLeaf(node.noise(), HOLDER_DESC);
            generateScaledCoord(ga, ctx, BLOCK_X, node.xzScale());
            generateScaledCoord(ga, ctx, BLOCK_Y, node.yScale());
            generateScaledCoord(ga, ctx, BLOCK_Z, node.xzScale());
            ga.invokeVirtual(NOISE_HOLDER_TYPE, GET_VALUE);

// 2. Дублируем double на стеке (занимает 2 слота на стеке)
            ga.dup2();

// 3. Сохраняем одну копию в локальную переменную, вторая остается для следующей ноды
            id = ga.newLocal(Type.DOUBLE_TYPE);
            ga.storeLocal(id);
            ctx.setVariable(node, id);
        }
    }

    private void generateScaledCoord(GeneratorAdapter ga, DCAsmContext ctx, String name, double scale) {
        // Оптимизация: умножение на 0
        if (scale == 0.0) {
            ga.push(0.0);
            return;
        }

        int var = ctx.getCachedVariable(name);
        if (var == -1) {
            throw new IllegalStateException("Variable " + name + " not loaded in Prepare step!");
        }

        // Загружаем int из кеша
        ga.loadLocal(var);
        // I2D
        ga.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);

        // Оптимизация: пропуск умножения на 1.0
        if (scale != 1.0) {
            ga.push(scale);
            ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
        }
    }
}
