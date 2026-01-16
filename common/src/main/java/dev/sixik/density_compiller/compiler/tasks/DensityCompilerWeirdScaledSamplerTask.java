package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerWeirdScaledSamplerTask extends DensityCompilerTask<DensityFunctions.WeirdScaledSampler> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";

    // Интерфейс Double2DoubleFunction (fastutil)
    private static final String MAPPER_INTERFACE = "it/unimi/dsi/fastutil/doubles/Double2DoubleFunction";

    // Внутренние имена классов (проверьте, соответствуют ли они вашей версии MC/Mapping)
    private static final String SAMPLER_CLASS = "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler";
    private static final String RARITY_ENUM = "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler$RarityValueMapper";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        // --- Шаг 1: Загрузка Mapper ---
        // Нам нужно, чтобы Mapper (объект) лежал на дне стека перед аргументом.
        loadMapper(mv, node, ctx);
        // Stack: [mapper]

        // --- Шаг 2: Вычисление аргумента (Input) ---
        // Генерируем код для input(). Результат (double) ляжет поверх mapper.
        ctx.visitNodeCompute(node.input());
        // Stack: [mapper, input_val]

        // --- Шаг 3: Вызов mapper.get(double) ---
        mv.visitMethodInsn(INVOKEINTERFACE, MAPPER_INTERFACE, "get", "(D)D", true);
        // Stack: [e]

        // --- Шаг 4: Сохраняем 'e' в переменную ---
        // Нам нужно 'e' для деления координат и для финального умножения.
        int varE = ctx.newLocalDouble();
        mv.visitInsn(DUP2); // Оставляем копию на стеке для шага 7
        mv.visitVarInsn(DSTORE, varE);
        // Stack: [e]

        // --- Шаг 5: Вызов NoiseHolder.getValue ---
        DensityFunction.NoiseHolder holder = node.noise();
        ctx.visitCustomLeaf(holder, HOLDER_DESC); // Stack: [e, holder]

        // Координаты X/e, Y/e, Z/e
        generateCoord(mv, ctx, "blockX", varE);
        generateCoord(mv, ctx, "blockY", varE);
        generateCoord(mv, ctx, "blockZ", varE);

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER_INTERNAL, "getValue", "(DDD)D", false);
        // Stack: [e, noise]

        // --- Шаг 6: Math.abs(noise) ---
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
        // Stack: [e, abs_noise]

        // --- Шаг 7: Умножение ---
        mv.visitInsn(DMUL);
        // Stack: [result]
    }

    /**
     * Загружает RarityValueMapper.mapper (Double2DoubleFunction) на стек.
     */
    private void loadMapper(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        // 1. Грузим сам объект WeirdScaledSampler как поле (Leaf)
        ctx.visitLeafReference(node);

        // 2. Кастим (так как поле типа DensityFunction)
        mv.visitTypeInsn(CHECKCAST, SAMPLER_CLASS);

        // 3. Достаем Enum RarityValueMapper
        mv.visitMethodInsn(INVOKEVIRTUAL, SAMPLER_CLASS, "rarityValueMapper", "()L" + RARITY_ENUM + ";", false);

        // 4. Достаем поле mapper из Enum
        mv.visitFieldInsn(GETFIELD, RARITY_ENUM, "mapper", "L" + MAPPER_INTERFACE + ";");
    }

    private void generateCoord(MethodVisitor mv, PipelineAsmContext ctx, String blockMethod, int varE) {
        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, blockMethod, "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DDIV);
    }

    private void loadCtxCoord(MethodVisitor mv, int loopCtx, String blockMethod) {
        mv.visitVarInsn(ALOAD, loopCtx);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, blockMethod, "()I", true);
        mv.visitInsn(I2D);
    }
}
