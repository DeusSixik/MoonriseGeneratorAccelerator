package dev.sixik.generator_accelerator.mixins.common_mixin.surface;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BiomeManager.class)
public interface GABiomeManagerAccess {
    @Accessor("biomeZoomSeed")
    long bts$getBiomeZoomSeed();

    @Accessor("noiseBiomeSource")
    BiomeManager.NoiseBiomeSource bts$getNoiseBiomeSource();
}
