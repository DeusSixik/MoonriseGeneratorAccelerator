package dev.sixik.generator_accelerator.common.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Optional no-allocation biome resolver hook used by compat mixins.
 */
public interface GARawBiomeResolver {
    boolean ga$hasRawBiomeLookup(Climate.Sampler sampler);

    Holder<Biome> ga$getRawNoiseBiome(int x, int y, int z, Climate.Sampler sampler, long[] scratchTarget);
}
