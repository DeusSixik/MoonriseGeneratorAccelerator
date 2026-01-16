package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerYClampedGradientTask extends DensityCompilerTask<DensityFunctions.YClampedGradient> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {

        /*
            This method uses functionContext.blockY() for calculations, so we will request the data.
         */
        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLOCK_Y_BITS);
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {

        /*
            After our request, the data should already exist, and we can
            retrieve the variable where functionContext.blockY() is stored.
         */
        int variable = ctx.getCachedVariable(DensityFunctionsCacheHandler.BLOCK_Y);
        ctx.readIntVar(variable);
        mv.visitInsn(I2D);

        mv.visitLdcInsn((double) node.fromY());
        mv.visitLdcInsn((double) node.toY());
        mv.visitLdcInsn(node.fromValue());
        mv.visitLdcInsn(node.toValue());

        DensityCompilerUtils.clampedMap(mv);

        /*
            We store the result in a variable before calculating it.
            Since the values are static, we don't need to calculate them every time.
         */
        int varId = ctx.createDoubleVarFromStack();
        ctx.putCachedVariable(String.valueOf(computeHash(node.fromY(), node.toY(), node.fromValue(), node.toValue())), varId);
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {

        /*
            Reading values from our variable
         */
        ctx.readDoubleVar(ctx.getCachedVariable(String.valueOf(computeHash(node.fromY(), node.toY(), node.fromValue(), node.toValue()))));
    }

    /**
     * Considers the cache key for writing to the registry of variables
     */
    protected static int computeHash(int fromY, int toY, double fromValue, double toValue) {
        long v1 = Double.doubleToRawLongBits(fromValue);
        long v2 = Double.doubleToRawLongBits(toValue);
        long h = 1;
        h = 31 * h + fromY;
        h = 31 * h + toY;
        h = 31 * h + (v1 ^ (v1 >>> 32));
        h = 31 * h + (v2 ^ (v2 >>> 32));

        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;

        return (int) h;
    }
}
