package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class DensityCompilerHolderHolderTask extends DensityCompilerTask<DensityFunctions.HolderHolder> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.HolderHolder node, Step step) {
        ctx.readNode(node.function().value(), step);
    }
}
