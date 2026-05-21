package dev.sixik.generator_accelerator.common.beardifier.region;

import dev.sixik.generator_accelerator.common.beardifier.GABeardifierPlan;

/**
 * Identity owner for shared 4x4 beardifier cell atlases.
 */
public final class GARegionalBeardifierAtlasOwner {
    private final GABeardifierPlan plan;
    private final int cellWidth;
    private final int cellHeight;
    private final int hash;

    public GARegionalBeardifierAtlasOwner(GABeardifierPlan plan, int cellWidth, int cellHeight) {
        this.plan = plan;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;

        int result = System.identityHashCode(plan);
        result = 31 * result + cellWidth;
        result = 31 * result + cellHeight;
        this.hash = result;
    }

    public GABeardifierPlan plan() {
        return this.plan;
    }

    public int cellWidth() {
        return this.cellWidth;
    }

    public int cellHeight() {
        return this.cellHeight;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GARegionalBeardifierAtlasOwner that)) {
            return false;
        }
        return this.plan == that.plan
                && this.cellWidth == that.cellWidth
                && this.cellHeight == that.cellHeight;
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
