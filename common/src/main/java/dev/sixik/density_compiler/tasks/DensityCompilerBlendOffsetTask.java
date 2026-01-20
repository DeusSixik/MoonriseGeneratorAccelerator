package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class DensityCompilerBlendOffsetTask extends DensityCompilerTask<DensityFunctions.BlendOffset> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.BlendOffset node, Step step) {
        if(step != Step.Compute) return;
        ctx.push(0.0D);
    }
}
