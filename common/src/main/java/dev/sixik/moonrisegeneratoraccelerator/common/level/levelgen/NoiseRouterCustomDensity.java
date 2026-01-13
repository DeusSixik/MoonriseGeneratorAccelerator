package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.world.level.levelgen.DensityFunction;

public interface NoiseRouterCustomDensity {

    DensityFunction[] bts$getDensity();

    void bts$setDensity(DensityFunction[] array);
}
