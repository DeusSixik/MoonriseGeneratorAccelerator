package dev.sixik.generator_accelerator.common.noise;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Marker for DFC-generated {@code fillArray} code: this provider iterates a fixed
 * X/Z lattice column of a {@link NoiseChunk}, varying only the noise-cell Y index.
 */
public interface DfcNoiseChunkSliceAccess {

    int AXIS_Y_COLUMN = 1;

    NoiseChunk noiseChunk();

    int sliceSizeY();

    default int axisMode() {
        return AXIS_Y_COLUMN;
    }
}
