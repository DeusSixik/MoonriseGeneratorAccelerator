package dev.sixik.generator_accelerator.common.features.vm;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Arrays;

@SuppressWarnings({"unchecked", "rawtypes"})
final class BiomeFilterScratch {
    private Holder<Biome>[] biomes = new Holder[8];
    private boolean[] hasFeature = new boolean[8];
    private int size;
    private ChunkGenerator generator;
    private PlacedFeature feature;

    void clear() {
        Arrays.fill(this.biomes, 0, this.size, null);
        this.size = 0;
        this.generator = null;
        this.feature = null;
    }

    boolean hasFeature(ChunkGenerator generator, Holder<Biome> biome, PlacedFeature feature) {
        if (this.generator != generator || this.feature != feature) {
            this.clear();
            this.generator = generator;
            this.feature = feature;
        }

        Holder<Biome>[] cachedBiomes = this.biomes;
        for (int i = 0, count = this.size; i < count; i++) {
            Holder<Biome> cached = cachedBiomes[i];
            if (cached == biome || cached.equals(biome)) {
                return this.hasFeature[i];
            }
        }

        boolean result = generator.getBiomeGenerationSettings(biome).hasFeature(feature);
        int index = this.size;
        if (index == cachedBiomes.length) {
            grow(index + 1);
            cachedBiomes = this.biomes;
        }
        cachedBiomes[index] = biome;
        this.hasFeature[index] = result;
        this.size = index + 1;
        return result;
    }

    private void grow(int capacity) {
        int next = this.biomes.length + (this.biomes.length >> 1) + 1;
        if (next < capacity) {
            next = capacity;
        }
        this.biomes = Arrays.copyOf(this.biomes, next);
        this.hasFeature = Arrays.copyOf(this.hasFeature, next);
    }
}
