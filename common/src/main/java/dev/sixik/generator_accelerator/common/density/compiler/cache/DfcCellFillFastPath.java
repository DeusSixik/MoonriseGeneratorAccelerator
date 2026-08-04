package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Optional guard for {@link DfcCellFillAccess} implementations whose fast path
 * is only available for some runtime child layouts. Structure beardifier fast-fill
 * remains an explicit opt-in because it is the visible-world corruption boundary.
 */
public interface DfcCellFillFastPath {
    boolean dfc$hasFastCellFillPath();

    String BEARDIFIER_FAST_FILL_PROPERTY = "ga.dfc.cellCacheFastFillers.beardifier";

    static DfcCellFillAccess asFastPath(DensityFunction function) {
        if (!(function instanceof DfcCellFillAccess access)) {
            return null;
        }
        if (function instanceof Beardifier && !Boolean.getBoolean(BEARDIFIER_FAST_FILL_PROPERTY)) {
            return null;
        }
        if (function instanceof DfcCellFillFastPath guarded && !guarded.dfc$hasFastCellFillPath()) {
            return null;
        }
        return access;
    }
}
