package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.NotNull;

public class OptimizationVisitor implements DensityFunction.Visitor{

    private static final DensityOptimizer optimizer = new DensityOptimizer();


    @Override
    public @NotNull DensityFunction apply(DensityFunction function) {
        return optimizer.optimize(function);
    }

    @Override
    public DensityFunction.@NotNull NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
        return noise;
    }
}
