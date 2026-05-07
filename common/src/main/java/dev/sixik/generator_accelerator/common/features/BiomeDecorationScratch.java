package dev.sixik.generator_accelerator.common.features;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Arrays;

public final class BiomeDecorationScratch {
    public final ObjectArraySet<Holder<Biome>> biomes = new ObjectArraySet<>();
    private final IntArrayList featureIndices = new IntArrayList(128);
    private int[] seenFeatureEpochs = new int[128];
    private int epoch = 1;

    public void beginStep(int featureCount) {
        this.featureIndices.clear();
        if (featureCount > this.seenFeatureEpochs.length) {
            int next = this.seenFeatureEpochs.length;
            while (next < featureCount) {
                next = next + (next >> 1) + 1;
            }
            this.seenFeatureEpochs = Arrays.copyOf(this.seenFeatureEpochs, next);
        }
        this.epoch++;
        if (this.epoch == 0) {
            Arrays.fill(this.seenFeatureEpochs, 0);
            this.epoch = 1;
        }
    }

    public void addFeatureIndex(int index) {
        if (this.seenFeatureEpochs[index] != this.epoch) {
            this.seenFeatureEpochs[index] = this.epoch;
            this.featureIndices.add(index);
        }
    }

    public int featureIndexCount() {
        return this.featureIndices.size();
    }

    public int[] sortedFeatureIndices() {
        int size = this.featureIndices.size();
        int[] indices = this.featureIndices.elements();
        Arrays.sort(indices, 0, size);
        return indices;
    }
}
