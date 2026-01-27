package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.noise;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

public record NoiseChunkSliceProvider(NoiseChunk noiseChunk) implements DensityFunction.ContextProvider {

    @Override
    public DensityFunction.FunctionContext forIndex(int i) {
        noiseChunk.cellStartBlockY = (i + noiseChunk.cellNoiseMinY) * noiseChunk.cellHeight;
        ++noiseChunk.interpolationCounter;
        noiseChunk.inCellY = 0;
        noiseChunk.arrayIndex = i;
        return noiseChunk;
    }

    @Override
    public void fillAllDirectly(double[] ds, DensityFunction densityFunction) {
        for (int i = 0; i < noiseChunk.cellCountY + 1; i++) {
            noiseChunk.cellStartBlockY = (i + noiseChunk.cellNoiseMinY) * noiseChunk.cellHeight;
            ++noiseChunk.interpolationCounter;
            noiseChunk.inCellY = 0;
            noiseChunk.arrayIndex = i;
            ds[i] = densityFunction.compute(noiseChunk);
        }
    }
}
