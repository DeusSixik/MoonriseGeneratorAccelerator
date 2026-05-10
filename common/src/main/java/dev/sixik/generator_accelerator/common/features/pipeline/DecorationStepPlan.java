package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public final class DecorationStepPlan {
    private static final DecorationKernelPlan[] EMPTY_KERNELS = new DecorationKernelPlan[0];
    private static final PlacedFeature[] EMPTY_FEATURES = new PlacedFeature[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final long[] EMPTY_LONGS = new long[0];

    private final int step;
    private final int featureCount;
    private final DecorationKernelPlan[] kernelsByFeatureIndex;
    private final PlacedFeature[] fallbackFeatures;
    private final int[] fallbackFeatureIndices;
    private final long[] descriptorFeatureMask;

    public DecorationStepPlan(
            int step,
            int featureCount,
            DecorationKernelPlan[] kernelsByFeatureIndex,
            PlacedFeature[] fallbackFeatures,
            int[] fallbackFeatureIndices,
            long[] descriptorFeatureMask
    ) {
        this.step = step;
        this.featureCount = featureCount;
        this.kernelsByFeatureIndex = kernelsByFeatureIndex == null ? EMPTY_KERNELS : kernelsByFeatureIndex;
        this.fallbackFeatures = fallbackFeatures == null ? EMPTY_FEATURES : fallbackFeatures;
        this.fallbackFeatureIndices = fallbackFeatureIndices == null ? EMPTY_INTS : fallbackFeatureIndices;
        this.descriptorFeatureMask = descriptorFeatureMask == null ? EMPTY_LONGS : descriptorFeatureMask;
    }

    public static DecorationStepPlan empty(int step) {
        return new DecorationStepPlan(step, 0, EMPTY_KERNELS, EMPTY_FEATURES, EMPTY_INTS, EMPTY_LONGS);
    }

    public int step() {
        return this.step;
    }

    public int featureCount() {
        return this.featureCount;
    }

    public DecorationKernelPlan kernelForFeatureIndex(int featureIndex) {
        if (featureIndex < 0 || featureIndex >= this.kernelsByFeatureIndex.length) {
            return null;
        }
        return this.kernelsByFeatureIndex[featureIndex];
    }

    public DecorationKernelPlan[] kernelsByFeatureIndex() {
        return this.kernelsByFeatureIndex;
    }

    public PlacedFeature[] fallbackFeatures() {
        return this.fallbackFeatures;
    }

    public int[] fallbackFeatureIndices() {
        return this.fallbackFeatureIndices;
    }

    public int fallbackFeatureCount() {
        return this.fallbackFeatures.length;
    }

    public boolean selectedNeedsDescriptors(long[] selectedFeatureMask, int wordCount) {
        if (selectedFeatureMask == null || wordCount <= 0 || this.descriptorFeatureMask.length == 0) {
            return false;
        }
        int limit = Math.min(wordCount, Math.min(selectedFeatureMask.length, this.descriptorFeatureMask.length));
        for (int wordIndex = 0; wordIndex < limit; wordIndex++) {
            if ((selectedFeatureMask[wordIndex] & this.descriptorFeatureMask[wordIndex]) != 0L) {
                return true;
            }
        }
        return false;
    }
}
