package dev.sixik.generator_accelerator.common.aquifer;

import net.minecraft.world.level.levelgen.DensityFunction;

public interface GAAquiferPrimitiveAccess {
    int GA_FALLBACK_RESULT = Integer.MIN_VALUE;

    int ga$computeSubstanceId(
            DensityFunction.FunctionContext context,
            double density,
            GAAquiferColumnBandNearest columnBand,
            GAAquiferNearest nearest
    );

    boolean ga$lastShouldScheduleFluidUpdate();

    byte ga$globalFluidKindAt(int x, int y, int z);

    int ga$globalFluidLevelAt(int x, int y, int z);
}
