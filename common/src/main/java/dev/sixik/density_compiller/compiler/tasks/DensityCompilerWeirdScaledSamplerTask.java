package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.PublicNoiseWrapper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerWeirdScaledSamplerTask extends DensityCompilerTask<DensityFunctions.WeirdScaledSampler> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String MAPPER_INTERFACE = "it/unimi/dsi/fastutil/doubles/Double2DoubleFunction";
    private static final String WRAPPER = Type.getInternalName(PublicNoiseWrapper.class);

    // Внутренние имена классов Minecraft (обрати внимание на $)
    private static final String SAMPLER_CLASS = "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler";
    private static final String RARITY_ENUM = "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler$RarityValueMapper";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        // 1. СНАЧАЛА загружаем Mapper (Target Object)
        loadMapper(mv, node, ctx);

        // 2. ПОТОМ вычисляем Input (Argument)
        ctx.visitNodeCompute(node.input());

        // 3. Вызываем get(double)
        mv.visitMethodInsn(INVOKEINTERFACE, MAPPER_INTERFACE, "get", "(D)D", true);

        // 4. FIX: Аллоцируем 2 слота под double
        int varE = ctx.newLocalDouble();
        mv.visitVarInsn(DSTORE, varE);

        // 5. Подготовка координат и вызов шума
        generateNoiseCall(mv, node, ctx, varE);

        // 6. Math.abs(noise) * e
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DMUL);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.input(), destArrayVar);

        // FIX: Аллоцируем 2 слота под double перед началом цикла
        // Это гарантирует, что iVar внутри цикла получит безопасный индекс (например, 9, а не 8)
        int varE = ctx.newLocalDouble();

        ctx.arrayForI(destArrayVar, (iVar) -> {
            ctx.startLoop();

            // --- Шаг 1: Считаем E ---
            loadMapper(mv, node, ctx);

            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD);

            mv.visitMethodInsn(INVOKEINTERFACE, MAPPER_INTERFACE, "get", "(D)D", true);
            mv.visitVarInsn(DSTORE, varE);

            // --- Шаг 2: Считаем Шум ---
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            generateNoiseCallFill(mv, node, ctx, iVar, varE);

            // --- Шаг 3: Финализация ---
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
            mv.visitVarInsn(DLOAD, varE);
            mv.visitInsn(DMUL);

            mv.visitInsn(DASTORE);
        });
    }

    /**
     * Загружает объект Double2DoubleFunction на стек.
     * Логика: Load Node (Leaf) -> Checkcast -> rarityValueMapper() -> .mapper
     */
    private void loadMapper(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx) {
        // Грузим саму ноду WeirdScaledSampler как лист (это DensityFunction, так что можно)
        ctx.visitLeafReference(node);

        // Кастим интерфейс DensityFunction к конкретному классу WeirdScaledSampler
        mv.visitTypeInsn(CHECKCAST, SAMPLER_CLASS);

        // Вызываем геттер enum-а
        mv.visitMethodInsn(INVOKEVIRTUAL, SAMPLER_CLASS, "rarityValueMapper", "()L" + RARITY_ENUM + ";", false);

        // Берем поле mapper из enum-а
        mv.visitFieldInsn(GETFIELD, RARITY_ENUM, "mapper", "L" + MAPPER_INTERFACE + ";");
    }

    private void generateNoiseCall(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx, int varE) {
        PublicNoiseWrapper noiseWrapper = new PublicNoiseWrapper(node.noise());
        ctx.visitLeafReference(noiseWrapper);
        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        // X / e
        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DDIV);

        // Y / e
        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DDIV);

        // Z / e
        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DDIV);

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);
    }

    private void generateNoiseCallFill(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, PipelineAsmContext ctx, int iVar, int varE) {
        PublicNoiseWrapper noiseWrapper = new PublicNoiseWrapper(node.noise());
        ctx.visitLeafReference(noiseWrapper);
        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        int loopCtx = ctx.getOrAllocateLoopContext(iVar);

        // X / e
        mv.visitVarInsn(ALOAD, loopCtx);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DDIV);

        // Y / e
        mv.visitVarInsn(ALOAD, loopCtx);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DDIV);

        // Z / e
        mv.visitVarInsn(ALOAD, loopCtx);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, varE);
        mv.visitInsn(DDIV);

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);
    }
}
