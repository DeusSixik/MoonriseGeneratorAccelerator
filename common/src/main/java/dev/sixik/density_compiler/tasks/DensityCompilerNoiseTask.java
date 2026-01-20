package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler.*;

public class DensityCompilerNoiseTask extends DensityCompilerTask<DensityFunctions.Noise> {

    private static final Type NOISE_HOLDER_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;");
    private static final Method GET_VALUE = Method.getMethod("double getValue(double, double, double)");

    private static final String HOLDER_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;";

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Noise node, Step step) {

        if (step == Step.Prepare) {
            ctx.putNeedCachedVariable(BLOCK_X_BITS | BLOCK_Y_BITS | BLOCK_Z_BITS);
            return;
        }

        if (step == Step.PostPrepare) {
            final GeneratorAdapter ga = ctx.mv();

            int varIdx = ga.newLocal(Type.DOUBLE_TYPE);
            ctx.putCachedVariable(getKey(node), varIdx);

            ctx.readLeaf(node.noise(), HOLDER_DESC);
            generateScaledCoord(ga, ctx, BLOCK_X, node.xzScale());
            generateScaledCoord(ga, ctx, BLOCK_Y, node.yScale());
            generateScaledCoord(ga, ctx, BLOCK_Z, node.xzScale());

            ga.invokeVirtual(NOISE_HOLDER_TYPE, GET_VALUE);
            ga.storeLocal(varIdx);
            return;
        }

        if (step == Step.Compute) {
            int varIdx = ctx.getCachedVariable(getKey(node));
            if (varIdx == -1) {
                throw new IllegalStateException("Noise node not pre-calculated!");
            }
            ctx.mv().loadLocal(varIdx);
        }
    }

    private void generateScaledCoord(GeneratorAdapter ga, DCAsmContext ctx, String name, double scale) {

        /*
            multiplication by 0
         */
        if (scale == 0.0) {
            ga.push(0.0);
            return;
        }

        int var = ctx.getCachedVariable(name);
        if (var == -1) {
            throw new IllegalStateException("Variable " + name + " not loaded in Prepare step!");
        }

        /*
            Loading int from the cache
         */
        ga.loadLocal(var);
        ga.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);

        /*
            skipping multiplication by 1.0
         */
        if (scale != 1.0) {
            ga.push(scale);
            ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
        }
    }

    private static String getKey(DensityFunctions.Noise node) {
        return node.hashCode() + "_" + node.xzScale() + "_" + node.yScale();
    }
}
