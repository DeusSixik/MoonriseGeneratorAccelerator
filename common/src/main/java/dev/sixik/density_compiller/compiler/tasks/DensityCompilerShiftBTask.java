package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftBTask extends DensityCompilerShiftTaskBase<DensityFunctions.ShiftB> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.ShiftB node, PipelineAsmContext ctx) {
        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLOCK_X_BITS, DensityFunctionsCacheHandler.BLOCK_Z_BITS);
    }

    @Override
    protected DensityFunction.NoiseHolder getHolder(DensityFunctions.ShiftB node) {
        return node.offsetNoise();
    }

    @Override
    protected void generateCoordinates(MethodVisitor mv, PipelineAsmContext ctx) {
        // Z * 0.25 (как первый аргумент X для шума)
        genCoord(mv, ctx, "blockZ");

        // X * 0.25 (как второй аргумент Y для шума)
        genCoord(mv, ctx, "blockX");

        // 0.0 (как третий аргумент Z для шума)
        mv.visitInsn(DCONST_0); // У тебя было DCONST_1, исправил на 0 по логике ShiftB
    }
}
