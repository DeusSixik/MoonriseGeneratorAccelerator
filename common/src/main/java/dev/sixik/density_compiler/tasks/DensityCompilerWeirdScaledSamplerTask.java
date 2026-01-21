package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler.*;

public class DensityCompilerWeirdScaledSamplerTask extends DensityCompilerTask<DensityFunctions.WeirdScaledSampler> {

    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";

    // Типы
    private static final Type NOISE_HOLDER_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;");
    private static final Type SAMPLER_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler;");
    private static final Type RARITY_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler$RarityValueMapper;");
    private static final Type MAPPER_INTERFACE_TYPE = Type.getType("Lit/unimi/dsi/fastutil/doubles/Double2DoubleFunction;");
    private static final Type MATH_TYPE = Type.getType(Math.class);

    // Методы
    private static final Method GET_NOISE_VALUE = Method.getMethod("double getValue(double, double, double)");
    private static final Method GET_MAPPER_VALUE = Method.getMethod("double get(double)");
    private static final Method GET_RARITY_MAPPER = Method.getMethod("net.minecraft.world.level.levelgen.DensityFunctions$WeirdScaledSampler$RarityValueMapper rarityValueMapper()");
    private static final Method ABS = Method.getMethod("double abs(double)");

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.WeirdScaledSampler node, Step step) {
        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_X_BITS | BLOCK_Y_BITS | BLOCK_Z_BITS);
            ctx.readNode(node.input(), Step.Prepare);
            return;
        }

        if (step != Step.Compute) {
            ctx.readNode(node.input(), step);
            return;
        }

        // 1. ПРОВЕРКА КЭША
        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        final GeneratorAdapter ga = ctx.mv();

        // 2. ИЗОЛЯЦИЯ В SCOPE
        // Используем scope, чтобы временные переменные (varRarity, varScale)
        // не конфликтовали с другими частями дерева.
        ctx.scope(() -> {
            // --- ВЫЧИСЛЕНИЕ RARITY (e) ---
            ctx.readLeaf(node);
            ga.checkCast(SAMPLER_TYPE);
            ga.invokeVirtual(SAMPLER_TYPE, GET_RARITY_MAPPER);
            ga.getField(RARITY_TYPE, "mapper", MAPPER_INTERFACE_TYPE);

            ctx.readNode(node.input(), Step.Compute);
            ga.invokeInterface(MAPPER_INTERFACE_TYPE, GET_MAPPER_VALUE);

            int varRarity = ga.newLocal(Type.DOUBLE_TYPE);
            ga.storeLocal(varRarity);

            // --- ВЫЧИСЛЕНИЕ NOISE ---
            ctx.readLeaf(node.noise(), HOLDER_DESC);

            // Координаты масштабируются как coord / rarity
            // Вместо вычисления 1.0/e и умножения, в "горячем пути"
            // иногда быстрее просто делить, но если JIT увидит 1/e, он сам оптимизирует.
            // Оставим твой вариант с scale = 1.0 / e.
            ga.push(1.0);
            ga.loadLocal(varRarity);
            ga.math(GeneratorAdapter.DIV, Type.DOUBLE_TYPE);
            int varScale = ga.newLocal(Type.DOUBLE_TYPE);
            ga.storeLocal(varScale);

            generateScaledCoord(ga, ctx, BLOCK_X, varScale);
            generateScaledCoord(ga, ctx, BLOCK_Y, varScale);
            generateScaledCoord(ga, ctx, BLOCK_Z, varScale);

            ga.invokeVirtual(NOISE_HOLDER_TYPE, GET_NOISE_VALUE);

            // --- ФИНАЛЬНЫЙ РЕЗУЛЬТАТ (noise * abs(rarity)) ---
            ga.loadLocal(varRarity);
            ga.invokeStatic(MATH_TYPE, ABS);
            ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);

            // 3. СОХРАНЕНИЕ В КЭШ НОДЫ
            int id = ga.newLocal(Type.DOUBLE_TYPE);
            ga.dup2();
            ga.storeLocal(id);
            ctx.setVariable(node, id);
        });
    }

    private void generateScaledCoord(GeneratorAdapter ga, DCAsmContext ctx, String blockVar, int scaleVarIndex) {
        // Загружаем координату (int)
        int varIndex = ctx.getCachedVariable(blockVar);
        ga.loadLocal(varIndex);
        ga.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);

        // Загружаем scale (double)
        ga.loadLocal(scaleVarIndex);

        // Умножаем
        ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
    }
}
