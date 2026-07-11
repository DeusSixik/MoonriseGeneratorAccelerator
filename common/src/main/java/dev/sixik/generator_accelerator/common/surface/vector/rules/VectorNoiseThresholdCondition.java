package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorNoiseThresholdCondition implements VectorCondition {
    private final ResourceKey<NormalNoise.NoiseParameters> noiseKey;
    private final double minThreshold;
    private final double maxThreshold;

    public VectorNoiseThresholdCondition(ResourceKey<NormalNoise.NoiseParameters> noise, double minThreshold, double maxThreshold) {
        this.noiseKey = noise;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        double[] columnNoiseCache = ctx.noiseColumnCache;
        int[] calculatedMarks = ctx.columnScratchMarks;
        int stamp = ctx.nextColumnScratchStamp();
        final NormalNoise noise = ctx.randomState.getOrCreateNoise(noiseKey);

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int xzIdx = localX | (localZ << 4);

                if (calculatedMarks[xzIdx] != stamp) {
                    int globalX = ctx.sectionStartX + localX;
                    int globalZ = ctx.sectionStartZ + localZ;
                    columnNoiseCache[xzIdx] = noise.getValue(globalX, 0.0, globalZ);
                    calculatedMarks[xzIdx] = stamp;
                }

                double noiseVal = columnNoiseCache[xzIdx];
                if (noiseVal < this.minThreshold || noiseVal > this.maxThreshold) {
                    activeMask.clear(i);
                }
                word &= word - 1L;
            }
        }
    }
}
