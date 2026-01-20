package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class DensityCompilerMarkerTask extends DensityCompilerTask<DensityFunctions.Marker> {
    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Marker node, Step step) {
        ctx.readNode(node.wrapped(), step);
    }
}
