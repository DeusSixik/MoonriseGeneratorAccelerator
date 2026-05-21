package dev.sixik.generator_accelerator.common.noise.region;

import java.util.function.Supplier;

/**
 * Immutable region-scoped facade over shared exact density slice products.
 */
public final class GARegionalDensityLatticeView {
    private final GARegionalDensitySliceCacheOwner owner;
    private final int regionX;
    private final int regionZ;

    public GARegionalDensityLatticeView(GARegionalDensitySliceCacheOwner owner, int regionX, int regionZ) {
        this.owner = owner;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    public GARegionalDensitySliceCacheOwner owner() {
        return this.owner;
    }

    public int regionX() {
        return this.regionX;
    }

    public int regionZ() {
        return this.regionZ;
    }

    public double[] sliceValues(int localSliceX, Supplier<double[]> builder) {
        return GARegionalDensitySliceCache.sliceValues(this.owner, this.regionX, this.regionZ, localSliceX, builder);
    }
}
