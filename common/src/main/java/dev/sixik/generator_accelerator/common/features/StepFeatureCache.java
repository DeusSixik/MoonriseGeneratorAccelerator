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
        long[][] byStep = this.featureDataFor(biome, generationSettingsGetter).masksByStep();
        long[] mask = byStep[step];
        return mask == null ? EMPTY_MASK : mask;
    }

    public long[][] featureMasksFor(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter) {
        return this.featureDataFor(biome, generationSettingsGetter).masksByStep();
    }

    public BiomeFeatureData featureDataFor(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter) {
        BiomeFeatureData cached = this.biomeFeatureData.get(biome);
        if (cached != null) {
            return cached;
        }
        BiomeFeatureData built = this.buildFeatureData(biome, generationSettingsGetter);
        BiomeFeatureData raced = this.biomeFeatureData.putIfAbsent(biome, built);
        return raced == null ? built : raced;
    }

    private BiomeFeatureData buildFeatureData(Holder<Biome> biome, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter) {
        long[][] byStep = new long[this.stepCount][];
        List<HolderSet<PlacedFeature>> placedFeatureSets = generationSettingsGetter.apply(biome).features();
        int maxStep = Math.min(this.stepCount, placedFeatureSets.size());
        int[] nonEmptySteps = new int[maxStep];
        int nonEmptyStepCount = 0;
        long nonEmptyStepBits = 0L;

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
            boolean stepNonEmpty = false;

            for (int holderIndex = 0; holderIndex < holderCount; holderIndex++) {
                int featureIndex = indexMapper.applyAsInt(holderSet.get(holderIndex).value());
                if (featureIndex >= 0 && featureIndex < featureCount) {
                    mask[featureIndex >>> 6] |= 1L << (featureIndex & 63);
                    stepNonEmpty = true;
                }
            }

            if (stepNonEmpty) {
                byStep[step] = mask;
                nonEmptySteps[nonEmptyStepCount++] = step;
                if (step < Long.SIZE) {
                    nonEmptyStepBits |= 1L << step;
                }
            } else {
                byStep[step] = EMPTY_MASK;
            }
        }

        return new BiomeFeatureData(byStep, copyOf(nonEmptySteps, nonEmptyStepCount), nonEmptyStepBits);
    }

    private static int[] copyOf(int[] source, int size) {
        int[] copy = new int[size];
        System.arraycopy(source, 0, copy, 0, size);
        return copy;
    }

    public static final class BiomeFeatureData {
        private final long[][] masksByStep;
        private final int[] nonEmptySteps;
        private final long nonEmptyStepBits;

        BiomeFeatureData(long[][] masksByStep, int[] nonEmptySteps, long nonEmptyStepBits) {
            this.masksByStep = masksByStep;
            this.nonEmptySteps = nonEmptySteps;
            this.nonEmptyStepBits = nonEmptyStepBits;
        }

        public long[][] masksByStep() {
            return this.masksByStep;
        }

        public int[] nonEmptySteps() {
            return this.nonEmptySteps;
        }

        public long nonEmptyStepBits() {
            return this.nonEmptyStepBits;
        }
    }
}
