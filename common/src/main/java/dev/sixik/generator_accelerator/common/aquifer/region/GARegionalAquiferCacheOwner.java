package dev.sixik.generator_accelerator.common.aquifer.region;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * Identity-based owner key for exact shared aquifer regional caches.
 */
public final class GARegionalAquiferCacheOwner {
    private final PositionalRandomFactory positionalRandomFactory;
    private final Aquifer.FluidPicker globalFluidPicker;
    private final Object erosionKey;
    private final Object depthKey;
    private final Object floodednessKey;
    private final int hash;

    public GARegionalAquiferCacheOwner(
            PositionalRandomFactory positionalRandomFactory,
            Aquifer.FluidPicker globalFluidPicker,
            Object erosionKey,
            Object depthKey,
            Object floodednessKey
    ) {
        this.positionalRandomFactory = positionalRandomFactory;
        this.globalFluidPicker = globalFluidPicker;
        this.erosionKey = erosionKey;
        this.depthKey = depthKey;
        this.floodednessKey = floodednessKey;

        int result = System.identityHashCode(positionalRandomFactory);
        result = 31 * result + System.identityHashCode(globalFluidPicker);
        result = 31 * result + System.identityHashCode(erosionKey);
        result = 31 * result + System.identityHashCode(depthKey);
        result = 31 * result + System.identityHashCode(floodednessKey);
        this.hash = result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GARegionalAquiferCacheOwner that)) {
            return false;
        }
        return this.positionalRandomFactory == that.positionalRandomFactory
                && this.globalFluidPicker == that.globalFluidPicker
                && this.erosionKey == that.erosionKey
                && this.depthKey == that.depthKey
                && this.floodednessKey == that.floodednessKey;
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
