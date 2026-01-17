package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerWeirdScaledSamplerTask extends DensityCompilerTask<DensityFunctions.WeirdScaledSampler> {

    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String MAPPER_INTERFACE = "it/unimi/dsi/fastutil/doubles/Double2DoubleFunction";
    private static final String SAMPLER_CLASS = "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler";
    private static final String RARITY_ENUM = "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler$RarityValueMapper";

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        // Запрашиваем кэширование координат.
        // Это заставит генератор сохранить X, Y, Z в локальные int переменные перед циклом.
        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLOCK_X_BITS, DensityFunctionsCacheHandler.BLOCK_Y_BITS, DensityFunctionsCacheHandler.BLOCK_Z_BITS);
        ctx.cache().needCachedForIndex = true;

        var machine = ctx.pipeline().stackMachine();
        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();
        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();
        ctx.comment("Owner: DensityCompilerWeirdScaledSamplerTask");

        // --- Шаг 1: Загрузка Mapper и вычисление Input ---
        loadMapper(mv, node, ctx); // Stack: [mapper]

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input()); // Stack: [mapper, input_val]
        machine.popStack();

        mv.visitMethodInsn(INVOKEINTERFACE, MAPPER_INTERFACE, "get", "(D)D", true);
        // Stack: [rarity_value] (мы называем это 'e' или 'rarity')

        // --- Шаг 2: Сохранение rarity и подготовка scale ---
        int varRarity = ctx.newLocalDouble();
        mv.visitVarInsn(DSTORE, varRarity); // Stack: []

        // Нам нужно делить координаты на rarity.
        // ОПТИМИЗАЦИЯ: Вместо 3 делений (DDIV) делаем 1 деление и 3 умножения.
        // scale = 1.0 / rarity
        mv.visitLdcInsn(1.0);
        mv.visitVarInsn(DLOAD, varRarity);
        mv.visitInsn(DDIV);

        int varScale = ctx.newLocalDouble();
        mv.visitVarInsn(DSTORE, varScale); // Stack: []

        // --- Шаг 3: Генерация координат и сэмплирование шума ---
        DensityFunction.NoiseHolder holder = node.noise();
        ctx.visitCustomLeaf(holder, HOLDER_DESC); // Stack: [holder]

        // X * scale
        generateFastCoord(mv, ctx, DensityFunctionsCacheHandler.BLOCK_X, varScale);
        // Y * scale
        generateFastCoord(mv, ctx, DensityFunctionsCacheHandler.BLOCK_Y, varScale);
        // Z * scale
        generateFastCoord(mv, ctx, DensityFunctionsCacheHandler.BLOCK_Z, varScale);

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER_INTERNAL, "getValue", "(DDD)D", false);
        // Stack: [noise_val]

        // --- Шаг 4: Финальное умножение ---
        // Formula: noise * Math.abs(rarity)

        mv.visitVarInsn(DLOAD, varRarity);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false); // Stack: [noise, abs(rarity)]
        mv.visitInsn(DMUL); // Stack: [result]
    }

    private void loadMapper(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        // Здесь можно было бы сделать switch по node.rarityValueMapper() и заинлайнить математику,
        // но загрузка через поле надежнее для совместимости.
        ctx.visitLeafReference(node);
        mv.visitTypeInsn(CHECKCAST, SAMPLER_CLASS);
        mv.visitMethodInsn(INVOKEVIRTUAL, SAMPLER_CLASS, "rarityValueMapper", "()L" + RARITY_ENUM + ";", false);
        mv.visitFieldInsn(GETFIELD, RARITY_ENUM, "mapper", "L" + MAPPER_INTERFACE + ";");
    }

    private void generateFastCoord(MethodVisitor mv, PipelineAsmContext ctx, String coordType, int varScale) {
        // 1. Грузим int координату прямо из локальной переменной (быстро!)
        int cachedVarIndex = ctx.getCachedVariable(coordType);
        ctx.readIntVar(cachedVarIndex);

        // 2. Конвертируем в double
        mv.visitInsn(I2D);

        // 3. Умножаем на прекалькулированный scale (1/e)
        mv.visitVarInsn(DLOAD, varScale);
        mv.visitInsn(DMUL);
    }
}
