package dev.sixik.density_compiler.task_base;

import dev.sixik.density_compiler.DCAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;

public abstract class DensityCompilerTask<T extends DensityFunction> {

    public final void applyStepImpl(DCAsmContext ctx, DensityFunction node, Step step) {
        applyStep(ctx, (T) node, step);
    }

    protected abstract void applyStep(DCAsmContext ctx, T node, Step step);

    public enum Step {
        Prepare,
        PostPrepare,
        CalculateSize,
        Compute
    }
}
