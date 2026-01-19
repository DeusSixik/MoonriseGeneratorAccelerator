package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class DensityCompilerConstantTask extends DensityCompilerTask<DensityFunctions.Constant> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Constant node, Step step) {
        if (step != Step.Compute) return;
        ctx.mv().visitLdcInsn(node.value());
    }
}
