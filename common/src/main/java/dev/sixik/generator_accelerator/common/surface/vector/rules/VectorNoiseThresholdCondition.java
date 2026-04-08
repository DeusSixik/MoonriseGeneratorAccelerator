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

        // Micro-cache with 256 columns to avoid noise calculation for each Y
        double[] columnNoiseCache = new double[256];
        boolean[] isNoiseCalculated = new boolean[256];

        final NormalNoise noise = ctx.randomState.getOrCreateNoise(noiseKey);

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int xzIdx = localX | (localZ << 4);

            if (!isNoiseCalculated[xzIdx]) {
                int globalX = ctx.sectionStartX + localX;
                int globalZ = ctx.sectionStartZ + localZ;
                columnNoiseCache[xzIdx] = noise.getValue(globalX, 0.0, globalZ);
                isNoiseCalculated[xzIdx] = true;
            }

            double noiseVal = columnNoiseCache[xzIdx];
            if (noiseVal < this.minThreshold || noiseVal > this.maxThreshold) {
                activeMask.clear(i);
            }
        }
    }
}
