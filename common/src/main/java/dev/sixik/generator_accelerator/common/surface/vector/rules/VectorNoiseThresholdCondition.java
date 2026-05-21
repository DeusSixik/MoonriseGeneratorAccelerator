package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.BitSet;

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
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        int stamp = ctx.nextColumnScratchStamp();

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int xzIdx = localX | (localZ << 4);
            double noiseVal = ctx.sampleNoiseColumn(this.noiseKey, localX, localZ, xzIdx, stamp);
            if (noiseVal < this.minThreshold || noiseVal > this.maxThreshold) {
                activeMask.clear(i);
            }
        }
    }
}
