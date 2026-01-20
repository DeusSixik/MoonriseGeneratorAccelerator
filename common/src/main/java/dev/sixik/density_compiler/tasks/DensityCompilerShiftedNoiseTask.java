package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler.*;

public class DensityCompilerShiftedNoiseTask extends DensityCompilerTask<DensityFunctions.ShiftedNoise> {

    // Типы и методы для ASM
    private static final Type NOISE_HOLDER_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;");
    private static final Method GET_VALUE = Method.getMethod("double getValue(double, double, double)");
    private static final Method UPDATE_INDICES = Method.getMethod("void updateIndices(net.minecraft.world.level.levelgen.DensityFunction$ContextProvider, int)");

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.ShiftedNoise node, Step step) {
        // 1. Prepare: Регистрируем переменные и спускаемся в детей
        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_X_BITS | BLOCK_Y_BITS | BLOCK_Z_BITS);

            ctx.readNode(node.shiftX(), Step.Prepare);
            ctx.readNode(node.shiftY(), Step.Prepare);
            ctx.readNode(node.shiftZ(), Step.Prepare);
            ctx.needInvokeNoiseChunk = true;
            return;
        }

        // 2. PostPrepare: Просто спускаемся в детей (вычислять здесь нельзя!)
        if (step == Step.PostPrepare) {
            ctx.readNode(node.shiftX(), Step.PostPrepare);
            ctx.readNode(node.shiftY(), Step.PostPrepare);
            ctx.readNode(node.shiftZ(), Step.PostPrepare);
            return;
        }

        if (step != Step.Compute) return;

        final GeneratorAdapter ga = ctx.mv();

        // --- ЛОГИКА ОБНОВЛЕНИЯ ИНДЕКСОВ (ARRAY CONTEXT) ---
        // Если мы работаем с массивом (Context == 2 по твоей логике), нужно обновить индексы в NoiseChunk,
        // так как ShiftedNoise использует сырые координаты (inCellX/Y/Z) внутри себя.

        // --- ГЕНЕРАЦИЯ ВЫЧИСЛЕНИЙ ---

        // 1. Загружаем Holder
        ctx.readLeaf(node.noise(), Type.getDescriptor(DensityFunction.NoiseHolder.class));

        // 2. Считаем координату X: (x * xzScale) + shiftX
        generateShiftedCoord(ga, ctx, node.xzScale(), BLOCK_X, node.shiftX());

        // 3. Считаем координату Y: (y * yScale) + shiftY
        generateShiftedCoord(ga, ctx, node.yScale(), BLOCK_Y, node.shiftY());

        // 4. Считаем координату Z: (z * xzScale) + shiftZ
        generateShiftedCoord(ga, ctx, node.xzScale(), BLOCK_Z, node.shiftZ());

        // 5. Вызываем getValue
        ga.invokeVirtual(NOISE_HOLDER_TYPE, GET_VALUE);
    }

    private void generateShiftedCoord(GeneratorAdapter ga, DCAsmContext ctx, double scale, String blockVar, DensityFunction shiftNode) {
        // Формула: (blockCoord * scale) + shiftValue

        // Часть A: blockCoord * scale
        int varIndex = ctx.getCachedVariable(blockVar);
        ga.loadLocal(varIndex);
        ga.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);

        if (scale != 1.0) {
            ga.push(scale);
            ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
        }
        // Если scale == 0.0, можно было бы оптимизировать,
        // но для ShiftedNoise это редкость, и мы все равно должны прибавить shiftNode.

        // Часть B: + shiftNode.compute()
        ctx.readNode(shiftNode, Step.Compute); // Результат (double) ложится на стек

        // A + B
        ga.math(GeneratorAdapter.ADD, Type.DOUBLE_TYPE);
    }

    /**
     * Runtime helper для обновления индексов в NoiseChunk.
     * Должен быть public static, чтобы ASM мог его вызвать.
     */
    public static void updateIndices(DensityFunction.ContextProvider provider, int index) {
        if (!(provider instanceof NoiseChunk chunk)) return;

        // Быстрая арифметика для степеней двойки
        int width = chunk.cellWidth;
        // height нам нужен для инверсии Y
        int height = chunk.cellHeight;

        int shift = Integer.numberOfTrailingZeros(width);
        int mask = width - 1;

        chunk.arrayIndex = index;
        chunk.inCellZ = index & mask;
        chunk.inCellX = (index >> shift) & mask;
        // Y идет в обратном порядке: (height - 1) - ...
        chunk.inCellY = (height - 1) - (index >> (shift + shift));
    }
}
