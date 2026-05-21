package dev.sixik.generator_accelerator.common.aquifer.region;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * Identity owner for exact 4x4 aquifer regional atlases.
 */
public final class GARegionalAquiferAtlasOwner {
    private final PositionalRandomFactory positionalRandomFactory;
    private final Aquifer.FluidPicker globalFluidPicker;
    private final Object erosionKey;
    private final Object depthKey;
    private final Object floodednessKey;
    private final int minGridX;
    private final int minGridY;
    private final int minGridZ;
    private final int gridSizeX;
    private final int gridSizeZ;
    private final int hash;

    public GARegionalAquiferAtlasOwner(
            PositionalRandomFactory positionalRandomFactory,
            Aquifer.FluidPicker globalFluidPicker,
            Object erosionKey,
            Object depthKey,
            Object floodednessKey,
            int minGridX,
            int minGridY,
            int minGridZ,
            int gridSizeX,
            int gridSizeZ
    ) {
        this.positionalRandomFactory = positionalRandomFactory;
        this.globalFluidPicker = globalFluidPicker;
        this.erosionKey = erosionKey;
        this.depthKey = depthKey;
        this.floodednessKey = floodednessKey;
        this.minGridX = minGridX;
        this.minGridY = minGridY;
        this.minGridZ = minGridZ;
        this.gridSizeX = gridSizeX;
        this.gridSizeZ = gridSizeZ;

        int result = System.identityHashCode(positionalRandomFactory);
        result = 31 * result + System.identityHashCode(globalFluidPicker);
        result = 31 * result + System.identityHashCode(erosionKey);
        result = 31 * result + System.identityHashCode(depthKey);
        result = 31 * result + System.identityHashCode(floodednessKey);
        result = 31 * result + minGridX;
        result = 31 * result + minGridY;
        result = 31 * result + minGridZ;
        result = 31 * result + gridSizeX;
        result = 31 * result + gridSizeZ;
        this.hash = result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GARegionalAquiferAtlasOwner that)) {
            return false;
        }
        return this.positionalRandomFactory == that.positionalRandomFactory
                && this.globalFluidPicker == that.globalFluidPicker
                && this.erosionKey == that.erosionKey
                && this.depthKey == that.depthKey
                && this.floodednessKey == that.floodednessKey
                && this.minGridX == that.minGridX
                && this.minGridY == that.minGridY
                && this.minGridZ == that.minGridZ
                && this.gridSizeX == that.gridSizeX
                && this.gridSizeZ == that.gridSizeZ;
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
