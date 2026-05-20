package dev.sixik.generator_accelerator.common.aquifer;

/**
 * Primitive, game-detached aquifer state and pressure math.
 */
public final class GAAquiferPlan {
    public static final int SOLID_RESULT = -1;

    public interface FluidLoader {
        void ga$loadAquiferFluid(int index);
    }

    public interface BarrierSampler {
        double ga$sampleBarrierNoise();
    }

    public static final class Result {
        public int blockId;
        public boolean solid;
        public boolean scheduleFluidUpdate;
    }

    private final GAAquiferGrid grid;
    private final GAAquiferFluidGrid fluids;
    private final int[] fluidBlockIds;
    private final int airBlockId;
    private final FluidLoader fluidLoader;

    public GAAquiferPlan(GAAquiferGrid grid, int airBlockId, FluidLoader fluidLoader) {
        this.grid = grid;
        this.fluids = new GAAquiferFluidGrid(grid.size());
        this.fluidBlockIds = new int[grid.size()];
        this.airBlockId = airBlockId;
        this.fluidLoader = fluidLoader;
    }

    public void nearest(int x, int y, int z, GAAquiferNearest out) {
        this.grid.nearest(x, y, z, out);
    }

    public void nearestColumnBand(int x, int y, int z, GAAquiferColumnBandNearest band, GAAquiferNearest out) {
        if (band == null) {
            this.grid.nearest(x, y, z, out);
            return;
        }
        this.grid.nearestColumnBand(x, y, z, band, out);
    }

    public boolean hasFluid(int index) {
        return this.fluids.has(index);
    }

    public void ensureFluid(int index) {
        if (!this.fluids.has(index)) {
            this.fluidLoader.ga$loadAquiferFluid(index);
        }
    }

    public void setFluid(int index, int fluidLevel, byte fluidKind, int fluidBlockId) {
        this.fluids.set(index, fluidLevel, fluidKind);
        this.fluidBlockIds[index] = fluidBlockId;
    }

    public int sampleX(int index) {
        return this.grid.sampleX(index);
    }

    public int sampleY(int index) {
        return this.grid.sampleY(index);
    }

    public int sampleZ(int index) {
        return this.grid.sampleZ(index);
    }

    public int blockIdAt(int index, int y) {
        return y < this.fluids.level(index) ? this.fluidBlockIds[index] : this.airBlockId;
    }

    public byte kindAt(int index, int y) {
        return this.fluids.kindAt(index, y);
    }

    public int fluidLevel(int index) {
        return this.fluids.level(index);
    }

    public void resolve(
            GAAquiferNearest nearest,
            int blockY,
            double density,
            boolean waterOverLava,
            double flowingUpdateSimilarity,
            BarrierSampler barrierSampler,
            Result out
    ) {
        int idx1 = nearest.idx1;
        ensureFluid(idx1);
        double d = similarity(nearest.dist1, nearest.dist2);
        int blockId = blockIdAt(idx1, blockY);
        if (d <= 0.0D) {
            out.blockId = blockId;
            out.solid = false;
            out.scheduleFluidUpdate = d >= flowingUpdateSimilarity;
            return;
        }
        if (waterOverLava) {
            out.blockId = blockId;
            out.solid = false;
            out.scheduleFluidUpdate = true;
            return;
        }

        int idx2 = nearest.idx2;
        ensureFluid(idx2);
        double e = d * pressureWithLazyBarrier(blockY, idx1, idx2, barrierSampler);
        if (density + e > 0.0D) {
            out.blockId = SOLID_RESULT;
            out.solid = true;
            out.scheduleFluidUpdate = false;
            return;
        }

        int idx3 = nearest.idx3;
        ensureFluid(idx3);
        double f = similarity(nearest.dist1, nearest.dist3);
        if (f > 0.0D) {
            double g = d * f * pressureWithLazyBarrier(blockY, idx1, idx3, barrierSampler);
            if (density + g > 0.0D) {
                out.blockId = SOLID_RESULT;
                out.solid = true;
                out.scheduleFluidUpdate = false;
                return;
            }
        }

        double h = similarity(nearest.dist2, nearest.dist3);
        if (h > 0.0D) {
            double i = d * h * pressureWithLazyBarrier(blockY, idx2, idx3, barrierSampler);
            if (density + i > 0.0D) {
                out.blockId = SOLID_RESULT;
                out.solid = true;
                out.scheduleFluidUpdate = false;
                return;
            }
        }

        out.blockId = blockId;
        out.solid = false;
        out.scheduleFluidUpdate = true;
    }

    public void resolveCell(
            GAAquiferNearest nearest,
            int blockY,
            double density,
            boolean waterOverLava,
            double flowingUpdateSimilarity,
            BarrierSampler barrierSampler,
            Result out
    ) {
        resolve(nearest, blockY, density, waterOverLava, flowingUpdateSimilarity, barrierSampler, out);
    }

    public void resolveColumnCell(
            int x,
            int y,
            int z,
            double density,
            GAAquiferColumnBandNearest band,
            GAAquiferNearest nearest,
            boolean waterOverLava,
            double flowingUpdateSimilarity,
            BarrierSampler barrierSampler,
            Result out
    ) {
        nearestColumnBand(x, y, z, band, nearest);
        resolve(nearest, y, density, waterOverLava, flowingUpdateSimilarity, barrierSampler, out);
    }

    public double pressure(int blockY, int firstIndex, int secondIndex, double barrierNoise) {
        int firstLevel = this.fluids.level(firstIndex);
        int secondLevel = this.fluids.level(secondIndex);
        byte firstKind = blockY < firstLevel ? this.fluids.kind(firstIndex) : GAAquiferFluidGrid.KIND_AIR;
        byte secondKind = blockY < secondLevel ? this.fluids.kind(secondIndex) : GAAquiferFluidGrid.KIND_AIR;
        if (isLavaWaterPair(firstKind, secondKind)) {
            return 2.0D;
        }
        int levelDiff = Math.abs(firstLevel - secondLevel);
        if (levelDiff == 0) {
            return 0.0D;
        }
        double meanLevel = 0.5D * (double) (firstLevel + secondLevel);
        double q = pressureQ(blockY, meanLevel, levelDiff);
        if (q < -2.0D || q > 2.0D) {
            return 2.0D * q;
        }
        return 2.0D * (barrierNoise + q);
    }

    private double pressureWithLazyBarrier(
            int blockY,
            int firstIndex,
            int secondIndex,
            BarrierSampler barrierSampler
    ) {
        int firstLevel = this.fluids.level(firstIndex);
        int secondLevel = this.fluids.level(secondIndex);
        byte firstKind = blockY < firstLevel ? this.fluids.kind(firstIndex) : GAAquiferFluidGrid.KIND_AIR;
        byte secondKind = blockY < secondLevel ? this.fluids.kind(secondIndex) : GAAquiferFluidGrid.KIND_AIR;
        if (isLavaWaterPair(firstKind, secondKind)) {
            return 2.0D;
        }
        int levelDiff = Math.abs(firstLevel - secondLevel);
        if (levelDiff == 0) {
            return 0.0D;
        }
        double meanLevel = 0.5D * (double) (firstLevel + secondLevel);
        double q = pressureQ(blockY, meanLevel, levelDiff);
        double barrier = (q >= -2.0D && q <= 2.0D) ? barrierSampler.ga$sampleBarrierNoise() : 0.0D;
        return 2.0D * (barrier + q);
    }

    public static double similarity(int firstDistance, int secondDistance) {
        return 1.0D - (double) Math.abs(secondDistance - firstDistance) * 0.04D;
    }

    public static boolean needsBarrierLevels(int blockY, int firstLevel, int secondLevel) {
        int levelDiff = Math.abs(firstLevel - secondLevel);
        if (levelDiff == 0) {
            return false;
        }
        double meanLevel = 0.5D * (double) (firstLevel + secondLevel);
        double q = pressureQ(blockY, meanLevel, levelDiff);
        return q >= -2.0D && q <= 2.0D;
    }

    public boolean needsBarrier(int blockY, int firstIndex, int secondIndex) {
        int firstLevel = this.fluids.level(firstIndex);
        int secondLevel = this.fluids.level(secondIndex);
        byte firstKind = blockY < firstLevel ? this.fluids.kind(firstIndex) : GAAquiferFluidGrid.KIND_AIR;
        byte secondKind = blockY < secondLevel ? this.fluids.kind(secondIndex) : GAAquiferFluidGrid.KIND_AIR;
        return !isLavaWaterPair(firstKind, secondKind)
                && needsBarrierLevels(blockY, firstLevel, secondLevel);
    }

    public static double pressureQ(double blockY, double meanLevel, double levelDiff) {
        double offset = blockY + 0.5D - meanLevel;
        double halfDiff = levelDiff * 0.5D;
        double inside = halfDiff - Math.abs(offset);
        if (offset > 0.0D) {
            if (inside > 0.0D) {
                return inside * 0.6666666666666666D;
            }
            return inside * 0.4D;
        }
        double lowerRamp = 3.0D + inside;
        if (lowerRamp > 0.0D) {
            return lowerRamp * 0.3333333333333333D;
        }
        return lowerRamp * 0.1D;
    }

    public static boolean isLavaWaterPair(byte firstKind, byte secondKind) {
        return (firstKind == GAAquiferFluidGrid.KIND_LAVA && secondKind == GAAquiferFluidGrid.KIND_WATER)
                || (firstKind == GAAquiferFluidGrid.KIND_WATER && secondKind == GAAquiferFluidGrid.KIND_LAVA);
    }
}
