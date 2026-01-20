package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class DensityCompilerBeardifierMarkerTask extends DensityCompilerTask<DensityFunctions.BeardifierMarker> {
    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.BeardifierMarker node, Step step) {
        if(step != Step.Compute) return;
        ctx.push(0.0);
    }
}
