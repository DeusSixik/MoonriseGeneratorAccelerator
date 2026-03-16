package dev.sixik.generator_accelerator.common.density.density_patch;

public interface NoiseChunkPatch {

    void prepareAllGrids();

    void setBlockPos(int x, int y, int z);
}
