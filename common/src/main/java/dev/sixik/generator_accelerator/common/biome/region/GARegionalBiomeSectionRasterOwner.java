package dev.sixik.generator_accelerator.common.biome.region;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Identity owner for cached live-section quart rasters.
 */
public final class GARegionalBiomeSectionRasterOwner {
    private final Object[] chunkKeys;
    private final int minBuildHeight;
    private final int buildHeight;
    private final int hash;

    public GARegionalBiomeSectionRasterOwner(ChunkAccess[] chunks, int minBuildHeight, int buildHeight) {
        this.chunkKeys = new Object[chunks.length];
        int result = minBuildHeight;
        result = 31 * result + buildHeight;
        for (int i = 0; i < chunks.length; i++) {
            this.chunkKeys[i] = chunks[i];
            result = 31 * result + System.identityHashCode(chunks[i]);
        }
        this.minBuildHeight = minBuildHeight;
        this.buildHeight = buildHeight;
        this.hash = result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GARegionalBiomeSectionRasterOwner that)) {
            return false;
        }
        if (this.minBuildHeight != that.minBuildHeight
                || this.buildHeight != that.buildHeight
                || this.chunkKeys.length != that.chunkKeys.length) {
            return false;
        }
        for (int i = 0; i < this.chunkKeys.length; i++) {
            if (this.chunkKeys[i] != that.chunkKeys[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
