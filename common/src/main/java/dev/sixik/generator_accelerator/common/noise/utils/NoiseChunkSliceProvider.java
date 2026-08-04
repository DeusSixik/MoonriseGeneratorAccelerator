package dev.sixik.generator_accelerator.common.noise.utils;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

public record NoiseChunkSliceProvider(NoiseChunk noiseChunk)
        implements DensityFunction.ContextProvider, DfcNoiseChunkSliceAccess {

    @Override
    public int sliceSizeY() {
        return noiseChunk.cellCountY + 1;
    }

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
