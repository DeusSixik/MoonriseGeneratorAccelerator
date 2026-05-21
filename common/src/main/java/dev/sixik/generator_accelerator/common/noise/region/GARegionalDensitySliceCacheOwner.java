package dev.sixik.generator_accelerator.common.noise.region;

/**
 * Identity owner for exact 4x4 regional NoiseChunk slice sharing.
 */
public final class GARegionalDensitySliceCacheOwner {
    private final Object blenderKey;
    private final Object noiseSettingsKey;
    private final int cellWidth;
    private final int cellHeight;
    private final int cellCountXZ;
    private final int cellCountY;
    private final int cellNoiseMinY;
    private final Object[] interpolatorKeys;
    private final int hash;

    public GARegionalDensitySliceCacheOwner(
            Object blenderKey,
            Object noiseSettingsKey,
            int cellWidth,
            int cellHeight,
            int cellCountXZ,
            int cellCountY,
            int cellNoiseMinY,
            Object[] interpolatorKeys
    ) {
        this.blenderKey = blenderKey;
        this.noiseSettingsKey = noiseSettingsKey;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.cellCountXZ = cellCountXZ;
        this.cellCountY = cellCountY;
        this.cellNoiseMinY = cellNoiseMinY;
        this.interpolatorKeys = interpolatorKeys.clone();

        int result = System.identityHashCode(blenderKey);
        result = 31 * result + System.identityHashCode(noiseSettingsKey);
        result = 31 * result + cellWidth;
        result = 31 * result + cellHeight;
        result = 31 * result + cellCountXZ;
        result = 31 * result + cellCountY;
        result = 31 * result + cellNoiseMinY;
        for (Object interpolatorKey : this.interpolatorKeys) {
            result = 31 * result + System.identityHashCode(interpolatorKey);
        }
        this.hash = result;
    }

    public int cellWidth() {
        return this.cellWidth;
    }

    public int cellCountXZ() {
        return this.cellCountXZ;
    }

    public int cellCountY() {
        return this.cellCountY;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GARegionalDensitySliceCacheOwner that)) {
            return false;
        }
        if (this.blenderKey != that.blenderKey
                || this.noiseSettingsKey != that.noiseSettingsKey
                || this.cellWidth != that.cellWidth
                || this.cellHeight != that.cellHeight
                || this.cellCountXZ != that.cellCountXZ
                || this.cellCountY != that.cellCountY
                || this.cellNoiseMinY != that.cellNoiseMinY
                || this.interpolatorKeys.length != that.interpolatorKeys.length) {
            return false;
        }
        for (int i = 0; i < this.interpolatorKeys.length; i++) {
            if (this.interpolatorKeys[i] != that.interpolatorKeys[i]) {
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
