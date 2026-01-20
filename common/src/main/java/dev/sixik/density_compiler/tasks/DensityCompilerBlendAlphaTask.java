package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class DensityCompilerBlendAlphaTask extends DensityCompilerTask<DensityFunctions.BlendAlpha> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.BlendAlpha node, Step step) {
        if(step != Step.Compute) return;
        ctx.push(1.0);
    }



}
