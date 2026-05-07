package dev.sixik.generator_accelerator.common.features;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class StepFeatureCache {
    private static final long[] EMPTY_MASK = new long[0];

    public final int stepCount;
    public final Object[][] featuresByStep;
    public final int[] featureMaskWordsByStep;
    private final ToIntFunction<PlacedFeature>[] indexMappings;
    private final ConcurrentHashMap<Holder<Biome>, BiomeFeatureData> biomeFeatureData = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public StepFeatureCache(List<FeatureSorter.StepFeatureData> featureData) {
        this.stepCount = featureData.size();
        this.featuresByStep = new Object[this.stepCount][];
        this.featureMaskWordsByStep = new int[this.stepCount];
        this.indexMappings = new ToIntFunction[this.stepCount];

        for (int step = 0; step < this.stepCount; step++) {
            FeatureSorter.StepFeatureData data = featureData.get(step);
            this.featuresByStep[step] = data.features().toArray();
            this.featureMaskWordsByStep[step] = (this.featuresByStep[step].length + Long.SIZE - 1) >>> 6;
            this.indexMappings[step] = data.indexMapping();
        }
    }

    public long[] featureMaskFor(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter, int step) {
        long[][] byStep = this.featureMasksFor(biome, generationSettingsGetter);
        long[] mask = byStep[step];
        return mask == null ? EMPTY_MASK : mask;
    }

    public long[][] featureMasksFor(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter) {
        return this.biomeFeatureData.computeIfAbsent(biome, holder -> this.buildFeatureData(holder, generationSettingsGetter)).masksByStep;
    }

    private BiomeFeatureData buildFeatureData(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter) {
        long[][] byStep = new long[this.stepCount][];
        List<HolderSet<PlacedFeature>> placedFeatureSets = generationSettingsGetter.apply(biome).features();
        int maxStep = Math.min(this.stepCount, placedFeatureSets.size());

        for (int step = 0; step < maxStep; step++) {
            HolderSet<PlacedFeature> holderSet = placedFeatureSets.get(step);
            int holderCount = holderSet.size();
            if (holderCount == 0) {
                byStep[step] = EMPTY_MASK;
                continue;
            }

            ToIntFunction<PlacedFeature> indexMapper = this.indexMappings[step];
            int featureCount = this.featuresByStep[step].length;
            int wordCount = this.featureMaskWordsByStep[step];
            if (featureCount == 0 || wordCount == 0) {
                byStep[step] = EMPTY_MASK;
                continue;
            }

            long[] mask = new long[wordCount];

            for (int holderIndex = 0; holderIndex < holderCount; holderIndex++) {
                int featureIndex = indexMapper.applyAsInt(holderSet.get(holderIndex).value());
                if (featureIndex >= 0 && featureIndex < featureCount) {
                    mask[featureIndex >>> 6] |= 1L << (featureIndex & 63);
                }
            }

            byStep[step] = mask;
        }

        return new BiomeFeatureData(byStep);
    }

    private record BiomeFeatureData(long[][] masksByStep) {
    }
}
