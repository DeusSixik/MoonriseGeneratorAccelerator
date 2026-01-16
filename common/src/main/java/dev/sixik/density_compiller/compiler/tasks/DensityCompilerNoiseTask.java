package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler.*;
import static dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler.BLOCK_Y_BITS;
import static dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler.BLOCK_Z_BITS;

public class DensityCompilerNoiseTask extends DensityCompilerTask<DensityFunctions.Noise> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";
    private static final String HOLDER_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.Noise node, PipelineAsmContext ctx) {
        ctx.putNeedCachedVariable(BLOCK_X_BITS, BLOCK_Y_BITS, BLOCK_Z_BITS);
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Noise node, PipelineAsmContext ctx) {
        // 1. Загружаем Holder
        ctx.visitCustomLeaf(node.noise(), HOLDER_DESC);

        // 2. Генерируем аргументы (X, Y, Z) с оптимизацией
        generateCoordinate(mv, ctx, BLOCK_X_BITS, node.xzScale());
        generateCoordinate(mv, ctx, BLOCK_Y_BITS, node.yScale());
        generateCoordinate(mv, ctx, BLOCK_Z_BITS, node.xzScale());

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER_INTERNAL, "getValue", "(DDD)D", false);
    }

    /**
     * Генерирует код для одной координаты: (ctx.blockN() * scale)
     */
    private void generateCoordinate(MethodVisitor mv, PipelineAsmContext ctx, int bits, double scale) {
        if (scale == 0.0) {
            mv.visitInsn(DCONST_0);
            return;
        }

        int variable = -1;

        if((bits & BLOCK_X_BITS) != 0) {
            variable = ctx.getCachedVariable(BLOCK_X);
        } else if((bits & BLOCK_Y_BITS) != 0) {
            variable = ctx.getCachedVariable(BLOCK_Y);
        } else if((bits & BLOCK_Z_BITS) != 0) {
            variable = ctx.getCachedVariable(BLOCK_Z);
        }

        if(variable == -1) {
            System.out.println("Variable not loaded!");
            ctx.pipeline().stackMachine().printDebug();

            throw new NullPointerException("Variable not loaded!");
        }

        ctx.readIntVar(variable);
        mv.visitInsn(I2D);

        if (scale != 1.0) {
            mv.visitLdcInsn(scale);
            mv.visitInsn(DMUL);
        }
    }
}