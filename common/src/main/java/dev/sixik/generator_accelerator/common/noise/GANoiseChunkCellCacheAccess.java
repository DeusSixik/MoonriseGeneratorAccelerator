package dev.sixik.generator_accelerator.common.noise;

public interface GANoiseChunkCellCacheAccess {
    boolean ga$lazyCellCachesEnabled();

    void ga$ensureCellCacheFilled(int index);
}
