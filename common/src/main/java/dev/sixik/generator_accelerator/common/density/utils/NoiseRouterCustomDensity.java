package dev.sixik.generator_accelerator.common.density.utils;

import net.minecraft.world.level.levelgen.DensityFunction;

public interface NoiseRouterCustomDensity {

    DensityFunction[] bts$getDensity();

    void bts$setDensity(DensityFunction[] array);
}
