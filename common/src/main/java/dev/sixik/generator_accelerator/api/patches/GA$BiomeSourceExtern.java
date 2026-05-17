package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;

public interface GA$BiomeSourceExtern {

    Set<Holder<Biome>> ga$getCache();

    Set<Holder<Biome>> ga$getCacheNotNull();

    void ga$setCache(Set<Holder<Biome>> biomes);

}
