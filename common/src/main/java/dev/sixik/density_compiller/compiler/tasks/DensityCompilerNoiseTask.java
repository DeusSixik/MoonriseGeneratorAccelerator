package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerNoiseTask extends DensityCompilerTask<DensityFunctions.Noise> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Noise node, PipelineAsmContext ctx) {
        // 1. Загружаем Holder
        ctx.visitCustomLeaf(node.noise(), HOLDER_DESC);

        // 2. Генерируем аргументы (X, Y, Z) с оптимизацией
        generateCoordinate(mv, ctx, "blockX", node.xzScale());
        generateCoordinate(mv, ctx, "blockY", node.yScale());
        generateCoordinate(mv, ctx, "blockZ", node.xzScale());

        // 3. Вызываем getValue
        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER_INTERNAL, "getValue", "(DDD)D", false);
    }

    /**
     * Генерирует код для одной координаты: (ctx.blockN() * scale)
     */
    private void generateCoordinate(MethodVisitor mv, PipelineAsmContext ctx, String blockMethodName, double scale) {
        // Оптимизация 1: Если scale 0, то результат всегда 0.0
        // Экономит: загрузку контекста, вызов метода, каст и умножение.
        if (scale == 0.0) {
            mv.visitInsn(DCONST_0);
            return;
        }

        // Загружаем координату: (double) ctx.blockN()
        ctx.loadContext();
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, blockMethodName, "()I", true);
        mv.visitInsn(I2D);

        // Оптимизация 2: Если scale 1.0, умножение не нужно
        if (scale != 1.0) {
            mv.visitLdcInsn(scale);
            mv.visitInsn(DMUL);
        }
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Noise node, PipelineAsmContext ctx, int destArrayVar) {
        DensityFunction.NoiseHolder holder = node.noise();

        // 1. Грузим поле Holder на стек
        ctx.visitCustomLeaf(holder, HOLDER_DESC);

        // 2. Сохраняем в локальную переменную (Ref), чтобы не дергать GETFIELD в цикле
        int holderVar = ctx.newLocalRef();
        mv.visitVarInsn(ASTORE, holderVar);

        double xzScale = node.xzScale();
        double yScale = node.yScale();

        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            // Грузим holder из локальной переменной
            mv.visitVarInsn(ALOAD, holderVar);

            // --- Подготовка координат для getValue ---
            int loopCtx = ctx.getOrAllocateLoopContext(iVar);
            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(loopCtx);

            generateCoordinate(mv, ctx, "blockX", xzScale);
            generateCoordinate(mv, ctx, "blockY", yScale);
            generateCoordinate(mv, ctx, "blockZ", xzScale);

            ctx.setCurrentContextVar(oldCtx);

            // Вызов getValue
            mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER_INTERNAL, "getValue", "(DDD)D", false);

            mv.visitInsn(DASTORE);
        });
    }
}