package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Arrays;

@SuppressWarnings({"unchecked", "rawtypes"})
final class PipelineBiomeFeatureCache {
    private Holder<Biome>[] biomes = new Holder[8];
    private boolean[] results = new boolean[8];
    private int size;
    private ChunkGenerator generator;
    private PlacedFeature feature;

    void clear() {
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

        Holder<Biome>[] cached = this.biomes;
        for (int i = 0, count = this.size; i < count; i++) {
            Holder<Biome> cachedBiome = cached[i];
            if (cachedBiome == biome || cachedBiome.equals(biome)) {
                return this.results[i];
            }
        }

        boolean result = generator.getBiomeGenerationSettings(biome).hasFeature(feature);
        int index = this.size;
        if (index == cached.length) {
            grow(index + 1);
            cached = this.biomes;
        }
        cached[index] = biome;
        this.results[index] = result;
        this.size = index + 1;
        return result;
    }

    private void grow(int capacity) {
        int next = this.biomes.length + (this.biomes.length >> 1) + 1;
        if (next < capacity) {
            next = capacity;
        }
        this.biomes = Arrays.copyOf(this.biomes, next);
        this.results = Arrays.copyOf(this.results, next);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }
}
