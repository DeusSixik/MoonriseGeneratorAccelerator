package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler.*;

public class DensityCompilerShiftTask extends DensityCompilerShiftTaskBase<DensityFunctions.Shift> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.Shift node, PipelineAsmContext ctx) {
        ctx.putNeedCachedVariable(
                BLOCK_X_BITS, BLOCK_Y_BITS, BLOCK_Z_BITS
        );
    }

    @Override
    protected DensityFunction.NoiseHolder getHolder(DensityFunctions.Shift node) {
        return node.offsetNoise();
    }

    @Override
    protected void generateCoordinates(MethodVisitor mv, PipelineAsmContext ctx) {
        genCoord(mv, ctx, "blockX");
        genCoord(mv, ctx, "blockY");
        genCoord(mv, ctx, "blockZ");
    }
}
