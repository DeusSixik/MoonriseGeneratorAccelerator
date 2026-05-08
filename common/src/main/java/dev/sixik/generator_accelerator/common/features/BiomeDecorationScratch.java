package dev.sixik.generator_accelerator.common.features;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Arrays;

public final class BiomeDecorationScratch {
    public final ObjectArraySet<Holder<Biome>> biomes = new ObjectArraySet<>();
    private final IntArrayList featureIndices = new IntArrayList(128);
    private long[][] combinedFeatureMasksByStep = new long[8][];
    private int combinedFeatureMaskStepCount;
    private long combinedNonEmptyStepBits;
    private long[] featureMask = new long[2];
    private int featureMaskWordCount;
    private final int[] biomePaletteIndices = new int[64];

    public void beginStep(int featureCount) {
        this.beginStepWords((featureCount + Long.SIZE - 1) >>> 6);
    }

    public void beginStepWords(int requiredWords) {
        if (requiredWords > this.featureMask.length) {
            int next = this.featureMask.length;
            while (next < requiredWords) {
                next = next + (next >> 1) + 1;
            }
            this.featureMask = Arrays.copyOf(this.featureMask, next);
        }
        if (requiredWords > 0) {
            Arrays.fill(this.featureMask, 0, requiredWords, 0L);
        }
        this.featureMaskWordCount = requiredWords;
    }

    public void beginCombinedFeatureMasks(int stepCount, int[] wordCountsByStep) {
        if (stepCount > this.combinedFeatureMasksByStep.length) {
            int next = this.combinedFeatureMasksByStep.length;
            while (next < stepCount) {
                next = next + (next >> 1) + 1;
            }
            this.combinedFeatureMasksByStep = Arrays.copyOf(this.combinedFeatureMasksByStep, next);
        }

        for (int step = 0; step < stepCount; step++) {
            int requiredWords = wordCountsByStep[step];
            long[] mask = this.combinedFeatureMasksByStep[step];
            if (mask == null || mask.length < requiredWords) {
                int next = mask == null ? 2 : mask.length;
                while (next < requiredWords) {
                    next = next + (next >> 1) + 1;
                }
                mask = new long[next];
                this.combinedFeatureMasksByStep[step] = mask;
            }
            if (requiredWords > 0) {
                Arrays.fill(mask, 0, requiredWords, 0L);
            }
        }
        this.combinedFeatureMaskStepCount = stepCount;
        this.combinedNonEmptyStepBits = 0L;
    }

    public int[] biomePaletteIndices() {
        return this.biomePaletteIndices;
    }

    public void addBiomeFeatureMasks(long[][] masksByStep, int[] wordCountsByStep) {
        int stepCount = Math.min(this.combinedFeatureMaskStepCount, masksByStep.length);
        for (int step = 0; step < stepCount; step++) {
            long[] source = masksByStep[step];
            if (source == null || source.length == 0) {
                continue;
            }

            int wordCount = Math.min(wordCountsByStep[step], source.length);
            if (wordCount <= 0) {
                continue;
            }

            long[] target = this.combinedFeatureMasksByStep[step];
            boolean stepNonEmpty = false;
            for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
                long value = source[wordIndex];
                target[wordIndex] |= value;
                stepNonEmpty |= value != 0L;
            }
            if (stepNonEmpty && step < Long.SIZE) {
                this.combinedNonEmptyStepBits |= 1L << step;
            }
        }
    }

    public void addBiomeFeatureData(StepFeatureCache.BiomeFeatureData featureData, int[] wordCountsByStep) {
        if (featureData == null) {
            return;
        }

        this.combinedNonEmptyStepBits |= featureData.nonEmptyStepBits();
        long[][] masksByStep = featureData.masksByStep();
        int[] nonEmptySteps = featureData.nonEmptySteps();
        for (int i = 0; i < nonEmptySteps.length; i++) {
            int step = nonEmptySteps[i];
            if (step < 0 || step >= this.combinedFeatureMaskStepCount || step >= masksByStep.length) {
                continue;
            }

            long[] source = masksByStep[step];
            if (source == null || source.length == 0) {
                continue;
            }

            int wordCount = Math.min(wordCountsByStep[step], source.length);
            if (wordCount <= 0) {
                continue;
            }

            long[] target = this.combinedFeatureMasksByStep[step];
            for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
                target[wordIndex] |= source[wordIndex];
            }
        }
    }

    public void clearBiomeFeatureMasks() {
        this.combinedFeatureMaskStepCount = 0;
        this.combinedNonEmptyStepBits = 0L;
    }

    public long combinedNonEmptyStepBits() {
        return this.combinedNonEmptyStepBits;
    }

    public void copyCombinedFeatureMasksFrom(long[][] sourceMasksByStep, long nonEmptyStepBits, int stepCount, int[] wordCountsByStep) {
        if (stepCount > this.combinedFeatureMasksByStep.length) {
            int next = this.combinedFeatureMasksByStep.length;
            while (next < stepCount) {
                next = next + (next >> 1) + 1;
            }
            this.combinedFeatureMasksByStep = Arrays.copyOf(this.combinedFeatureMasksByStep, next);
        }

        for (int step = 0; step < stepCount; step++) {
            int requiredWords = wordCountsByStep[step];
            long[] target = this.combinedFeatureMasksByStep[step];
            if (target == null || target.length < requiredWords) {
                int next = target == null ? 2 : target.length;
                while (next < requiredWords) {
                    next = next + (next >> 1) + 1;
                }
                target = new long[next];
                this.combinedFeatureMasksByStep[step] = target;
            }

            long[] source = sourceMasksByStep != null && step < sourceMasksByStep.length ? sourceMasksByStep[step] : null;
            if (requiredWords <= 0) {
                continue;
            }
            if (source == null || source.length == 0) {
                Arrays.fill(target, 0, requiredWords, 0L);
                continue;
            }

            int copyWords = Math.min(requiredWords, source.length);
            System.arraycopy(source, 0, target, 0, copyWords);
            if (copyWords < requiredWords) {
                Arrays.fill(target, copyWords, requiredWords, 0L);
            }
        }
        this.combinedFeatureMaskStepCount = stepCount;
        this.combinedNonEmptyStepBits = nonEmptyStepBits;
    }

    public long[] featureMaskForStep(int step) {
        return this.combinedFeatureMasksByStep[step];
    }

    public boolean stepHasFeatures(int step) {
        return step >= 0
                && step < this.combinedFeatureMaskStepCount
                && step < Long.SIZE
                && (this.combinedNonEmptyStepBits & (1L << step)) != 0L;
    }

    public int featureIndexCount() {
        return this.featureIndices.size();
    }

    public int[] collectFeatureIndices(long[] featureMask, int wordCount) {
        this.featureIndices.clear();
        if (featureMask == null || wordCount <= 0) {
            return this.featureIndices.elements();
        }
        int limit = Math.min(wordCount, featureMask.length);
        for (int wordIndex = 0; wordIndex < limit; wordIndex++) {
            long bits = featureMask[wordIndex];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                this.featureIndices.add((wordIndex << 6) + bit);
                bits &= bits - 1L;
            }
        }
        return this.featureIndices.elements();
    }

    public void addFeatureMask(long[] mask) {
        if (mask.length == 0) {
            return;
        }
        int wordCount = Math.min(mask.length, this.featureMaskWordCount);
        for (int i = 0; i < wordCount; i++) {
            this.featureMask[i] |= mask[i];
        }
    }

    public int[] collectFeatureIndices() {
        return this.collectFeatureIndices(this.featureMask, this.featureMaskWordCount);
    }
}
