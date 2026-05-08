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
            for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
                target[wordIndex] |= source[wordIndex];
            }
        }
    }

    public void clearBiomeFeatureMasks() {
        this.combinedFeatureMaskStepCount = 0;
    }

    public long[] featureMaskForStep(int step) {
        return this.combinedFeatureMasksByStep[step];
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
