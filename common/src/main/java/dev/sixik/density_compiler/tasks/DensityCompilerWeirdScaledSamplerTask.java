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
        // 1. Prepare: кэшируем координаты и спускаемся в input
        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_X_BITS | BLOCK_Y_BITS | BLOCK_Z_BITS);
            ctx.readNode(node.input(), Step.Prepare);
            return;
        }

        // 2. PostPrepare: рекурсия
        if (step == Step.PostPrepare) {
            ctx.readNode(node.input(), Step.PostPrepare);
            return;
        }

        if (step != Step.Compute) return;

        final GeneratorAdapter ga = ctx.mv();

        // --- ШАГ 1: Загрузка Mapper и вычисление Rarity (e) ---

        // Грузим объект WeirdScaledSampler (node)
        ctx.readLeaf(node);
        ga.checkCast(SAMPLER_TYPE);

        // Вызываем rarityValueMapper() -> RarityValueMapper Enum
        ga.invokeVirtual(SAMPLER_TYPE, GET_RARITY_MAPPER);

        // Читаем поле mapper -> Double2DoubleFunction
        ga.getField(RARITY_TYPE, "mapper", MAPPER_INTERFACE_TYPE);

        // Вычисляем input
        ctx.readNode(node.input(), Step.Compute);

        // Вызываем mapper.get(input)
        ga.invokeInterface(MAPPER_INTERFACE_TYPE, GET_MAPPER_VALUE);

        // Сохраняем 'rarity' (e) в локальную переменную
        int varRarity = ga.newLocal(Type.DOUBLE_TYPE);
        ga.storeLocal(varRarity);

        // --- ШАГ 2: Вычисление Scale (1.0 / e) ---
        // Оптимизация: деление дорогое, лучше поделить 1 раз и потом умножать
        ga.push(1.0);
        ga.loadLocal(varRarity);
        ga.math(GeneratorAdapter.DIV, Type.DOUBLE_TYPE);

        int varScale = ga.newLocal(Type.DOUBLE_TYPE);
        ga.storeLocal(varScale);

        // --- ШАГ 3: Вычисление Noise ---

        // Грузим NoiseHolder
        ctx.readLeaf(node.noise(), HOLDER_DESC);

        // Генерируем аргументы: coord * scale
        generateScaledCoord(ga, ctx, BLOCK_X, varScale);
        generateScaledCoord(ga, ctx, BLOCK_Y, varScale);
        generateScaledCoord(ga, ctx, BLOCK_Z, varScale);

        // Вызываем NoiseHolder.getValue(x, y, z)
        ga.invokeVirtual(NOISE_HOLDER_TYPE, GET_NOISE_VALUE);

        // --- ШАГ 4: Финальный результат (noise * abs(rarity)) ---
        ga.loadLocal(varRarity);
        ga.invokeStatic(MATH_TYPE, ABS);
        ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
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
