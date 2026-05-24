package dev.sixik.generator_accelerator.common.surface;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.function.Supplier;

public interface SurfaceRulesContextBiomeGetter {

    Supplier<Holder<Biome>> bts$getBiomeSupplier();

    Holder<Biome> bts$getBiomeHolderCached();

    Biome bts$getBiomeCached();

    int bts$getPositionX();

    int bts$getPositionY();

    int bts$getPositionZ();
}
