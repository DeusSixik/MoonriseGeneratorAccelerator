package dev.sixik.generator_accelerator.common.features;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class StepFeatureCache {
    private static final int[] EMPTY = new int[0];

    public final int stepCount;
    public final Object[][] featuresByStep;
    public final ToIntFunction<PlacedFeature>[] indexMappings;
    private final ConcurrentHashMap<Holder<Biome>, int[][]> biomeFeatureIndices = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public StepFeatureCache(List<FeatureSorter.StepFeatureData> featureData) {
        this.stepCount = featureData.size();
        this.featuresByStep = new Object[this.stepCount][];
        this.indexMappings = new ToIntFunction[this.stepCount];

        for (int step = 0; step < this.stepCount; step++) {
            FeatureSorter.StepFeatureData data = featureData.get(step);
            this.featuresByStep[step] = data.features().toArray();
            this.indexMappings[step] = data.indexMapping();
        }
    }

    public int[] indicesFor(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter, int step) {
        int[][] byStep = this.biomeFeatureIndices.computeIfAbsent(biome, holder -> this.buildIndices(holder, generationSettingsGetter));
        int[] indices = byStep[step];
        return indices == null ? EMPTY : indices;
    }

    private int[][] buildIndices(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter) {
        int[][] byStep = new int[this.stepCount][];
        List<HolderSet<PlacedFeature>> placedFeatureSets = generationSettingsGetter.apply(biome).features();
        int maxStep = Math.min(this.stepCount, placedFeatureSets.size());

        for (int step = 0; step < maxStep; step++) {
            HolderSet<PlacedFeature> holderSet = placedFeatureSets.get(step);
            int holderCount = holderSet.size();
            if (holderCount == 0) {
                byStep[step] = EMPTY;
                continue;
            }

            int[] tmp = new int[holderCount];
            int count = 0;
            ToIntFunction<PlacedFeature> indexMapper = this.indexMappings[step];
            int featureCount = this.featuresByStep[step].length;

            for (int holderIndex = 0; holderIndex < holderCount; holderIndex++) {
                int featureIndex = indexMapper.applyAsInt(holderSet.get(holderIndex).value());
                if (featureIndex >= 0 && featureIndex < featureCount) {
                    tmp[count++] = featureIndex;
                }
            }

            byStep[step] = count == 0 ? EMPTY : count == holderCount ? tmp : Arrays.copyOf(tmp, count);
        }

        return byStep;
    }
}
