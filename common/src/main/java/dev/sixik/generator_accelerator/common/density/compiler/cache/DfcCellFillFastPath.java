package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Optional guard for {@link DfcCellFillAccess} implementations whose fast path
 * is only available for some runtime child layouts.
 */
public interface DfcCellFillFastPath {
    boolean dfc$hasFastCellFillPath();

    static DfcCellFillAccess asFastPath(DensityFunction function) {
        if (!(function instanceof DfcCellFillAccess access)) {
            return null;
        }
        if (function instanceof DfcCellFillFastPath guarded && !guarded.dfc$hasFastCellFillPath()) {
            return null;
        }
        return access;
    }
}
