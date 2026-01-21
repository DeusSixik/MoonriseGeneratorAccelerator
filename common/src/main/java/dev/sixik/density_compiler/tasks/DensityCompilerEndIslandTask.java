package dev.sixik.density_compiler.tasks;

import dev.sixik.asm.utils.DescriptorBuilder;
import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import dev.sixik.density_compiler.utils.helpers.EndIslandHelper;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;

public class DensityCompilerEndIslandTask extends DensityCompilerTask<DensityFunctions.EndIslandDensityFunction> {
    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.EndIslandDensityFunction node, Step step) {

        if(step == Step.Prepare) {
            ctx.needCachedForIndex = true;
            return;
        }

        if(step != Step.Compute) return;

        final var cachedId = ctx.getVariable(node);
        if(cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        ctx.readLeaf(node);
        ctx.readContext();
        ctx.invokeMethodStatic(
                EndIslandHelper.class,
                "fastCompute",
                DescriptorBuilder.builder()
                        .type(DensityFunctions.EndIslandDensityFunction.class)
                        .type(DensityFunction.FunctionContext.class)
                        .buildMethod(double.class)
        );

        int id = ctx.mv().newLocal(Type.DOUBLE_TYPE);
        ctx.mv().dup2();
        ctx.mv().storeLocal(id);
        ctx.setVariable(node, id);
    }
}
