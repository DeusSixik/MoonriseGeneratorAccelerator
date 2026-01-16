package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerYClampedGradientTask extends DensityCompilerTask<DensityFunctions.YClampedGradient> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {
        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLOCK_Y_BITS);
    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {
        int variable = ctx.getCachedVariable(DensityFunctionsCacheHandler.BLOCK_Y);
        ctx.readIntVar(variable);
        mv.visitInsn(I2D);

        mv.visitLdcInsn((double) node.fromY());
        mv.visitLdcInsn((double) node.toY());
        mv.visitLdcInsn(node.fromValue());
        mv.visitLdcInsn(node.toValue());

        DensityCompilerUtils.clampedMap(mv);

        int varId = ctx.createDoubleVarFromStack();
        ctx.putCachedVariable(String.valueOf(computeHash(node.fromY(), node.toY(),node.fromValue(), node.toValue())), varId);
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {
//        // (double) functionContext.blockY()
////        ctx.loadContext();
//
//        ctx.loadBlockY();
////        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
////        mv.visitInsn(I2D);
//
//        // Параметры градиента
//        mv.visitLdcInsn((double) node.fromY());
//        mv.visitLdcInsn((double) node.toY());
//        mv.visitLdcInsn(node.fromValue());
//        mv.visitLdcInsn(node.toValue());
//
//        // Используем инлайновую версию для скорости
//        DensityCompilerUtils.clampedMap(mv);

        ctx.readDoubleVar(ctx.getCachedVariable(String.valueOf(computeHash(node.fromY(), node.toY(),node.fromValue(), node.toValue()))));
    }

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
