package dev.sixik.generator_accelerator.common.features;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Arrays;

public final class BiomeDecorationScratch {
    public final ObjectArraySet<Holder<Biome>> biomes = new ObjectArraySet<>();
    private final IntArrayList featureIndices = new IntArrayList(128);
    private long[] featureMask = new long[2];
    private int featureMaskWordCount;

    public void beginStep(int featureCount) {
        this.featureIndices.clear();
        int requiredWords = (featureCount + Long.SIZE - 1) >>> 6;
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

    public void addFeatureMask(long[] mask) {
        if (mask.length == 0) {
            return;
        }
        int wordCount = Math.min(mask.length, this.featureMaskWordCount);
        for (int i = 0; i < wordCount; i++) {
            this.featureMask[i] |= mask[i];
        }
    }

    public int featureIndexCount() {
        return this.featureIndices.size();
    }

    public int[] collectFeatureIndices() {
        this.featureIndices.clear();
        for (int wordIndex = 0; wordIndex < this.featureMaskWordCount; wordIndex++) {
            long bits = this.featureMask[wordIndex];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                this.featureIndices.add((wordIndex << 6) + bit);
                bits &= bits - 1L;
            }
        }
        return this.featureIndices.elements();
    }
}
