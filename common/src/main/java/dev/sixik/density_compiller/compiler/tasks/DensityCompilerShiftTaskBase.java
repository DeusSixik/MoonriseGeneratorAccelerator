package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

import static dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler.*;

public abstract class DensityCompilerShiftTaskBase<T extends DensityFunction> extends DensityCompilerTask<T> {

    protected static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    protected static final String HOLDER_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";

    // Абстрактный метод для генерации координат
    protected abstract void generateCoordinates(MethodVisitor mv, PipelineAsmContext ctx);

    // Абстрактный метод для получения холдера из ноды
    protected abstract DensityFunction.NoiseHolder getHolder(T node);

    @Override
    protected void compileCompute(MethodVisitor mv, T node, PipelineAsmContext ctx) {
        DensityFunction.NoiseHolder holder = getHolder(node);

        // 1. Грузим Holder
        ctx.visitCustomLeaf(holder, HOLDER_DESC);

        // 2. Генерируем координаты (X, Y, Z) для NoiseHolder.getValue(x, y, z)
        generateCoordinates(mv, ctx);

        // 3. Вызываем NoiseHolder.getValue(d, d, d)
        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER_INTERNAL, "getValue", "(DDD)D", false);

        // 4. Умножаем результат на 4.0
        mv.visitLdcInsn(4.0D);
        mv.visitInsn(DMUL);
    }

    // Хелпер для генерации (coord * 0.25)
    protected void genCoord(
            MethodVisitor mv,
            PipelineAsmContext ctx,
            String blockMethod
    ) {

       int var = switch (blockMethod) {
            case "blockX" -> ctx.getCachedVariable(BLOCK_X);
            case "blockY" -> ctx.getCachedVariable(BLOCK_Y);
            case "blockZ" -> ctx.getCachedVariable(BLOCK_Z);
           default -> throw new IllegalStateException("Unexpected value: " + blockMethod);
       };

       ctx.readIntVar(var);
       ctx.mv().visitInsn(I2D);

        mv.visitLdcInsn(0.25D);
        mv.visitInsn(DMUL);
    }
}
