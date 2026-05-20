package dev.sixik.generator_accelerator.common.beardifier;

import java.util.Arrays;

/**
 * Pure primitive Beardifier math. Minecraft objects are adapted into
 * {@link GABeardifierPlan} before this code runs.
 */
public final class GABeardifierKernel {
    public static final byte KIND_NONE = 0;
    public static final byte KIND_BURY = 1;
    public static final byte KIND_BEARD_THIN = 2;
    public static final byte KIND_BEARD_BOX = 3;
    public static final byte KIND_ENCAPSULATE = 4;

    private static volatile float[] beardKernel;
    private static volatile float[] beardSameY;
    private static final double[] BURY_HALF_Y = initBuryHalfYTable();
    private static final double[] ENCAPSULATE_HALF = initEncapsulateHalfTable();
    private static final double[] ENCAPSULATE_HALF_0_8 = initScaledTable(ENCAPSULATE_HALF, 0.8D);

    private GABeardifierKernel() {
    }

    public static void setBeardKernel(float[] kernel) {
        if (kernel == null || kernel.length < 24 * 24 * 24) {
            throw new IllegalArgumentException("beard kernel must contain 24^3 entries");
        }
        if (beardKernel != kernel) {
            beardKernel = kernel;
            beardSameY = null;
        }
    }

    public static boolean hasKernel() {
        return beardKernel != null;
    }

    public static double computeAt(GABeardifierPlan plan, int x, int y, int z) {
        if (plan == null || plan.outsidePoint(x, y, z)) {
            return 0.0D;
        }
        double value = 0.0D;
        byte[] terrain = plan.pieceTerrain;
        for (int p = 0; p < terrain.length; p++) {
            if (outsidePiece(plan, p, x, y, z)) {
                continue;
            }
            int dx = distanceToRange(x, plan.pieceMinX[p], plan.pieceMaxX[p]);
            int dz = distanceToRange(z, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
            switch (terrain[p]) {
                case KIND_BURY -> value += getBuryContributionHalfY(dx, y - plan.pieceGroundY[p], dz);
                case KIND_BEARD_THIN -> value += getBeardContributionSameY(dx, y - plan.pieceGroundY[p], dz) * 0.8D;
                case KIND_BEARD_BOX -> {
                    int yDistance = distanceToRange(y, plan.pieceGroundY[p], plan.pieceMaxY[p]);
                    value += getBeardContributionUnchecked(dx, yDistance, dz, y - plan.pieceGroundY[p]) * 0.8D;
                }
                case KIND_ENCAPSULATE -> {
                    int dy = distanceToRange(y, plan.pieceMinY[p], plan.pieceMaxY[p]);
                    value += getEncapsulateContributionScaled(dx, dy, dz);
                }
                default -> {
                }
            }
        }
        value += computeJunctionAt(plan, x, y, z);
        return value;
    }

    public static double computeAt(GABeardifierPlan plan, GABeardifierCellScratch scratch, int x, int y, int z) {
        if (plan == null || plan.outsidePoint(x, y, z)) {
            return 0.0D;
        }
        if (scratch == null || !plan.hasSpatialIndex()) {
            return computeAt(plan, x, y, z);
        }
        plan.collectActive(scratch, x, x, y, y, z, z);
        if (scratch.empty()) {
            return 0.0D;
        }
        return computeActiveAt(plan, scratch, x, y, z);
    }

    private static double computeActiveAt(GABeardifierPlan plan, GABeardifierCellScratch scratch, int x, int y, int z) {
        double value = 0.0D;
        int[] active = scratch.buryPieces;
        for (int a = 0; a < scratch.buryCount; a++) {
            int p = active[a];
            int dx = distanceToRange(x, plan.pieceMinX[p], plan.pieceMaxX[p]);
            int dz = distanceToRange(z, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
            value += getBuryContributionHalfY(dx, y - plan.pieceGroundY[p], dz);
        }
        active = scratch.thinPieces;
        for (int a = 0; a < scratch.thinCount; a++) {
            int p = active[a];
            int dx = distanceToRange(x, plan.pieceMinX[p], plan.pieceMaxX[p]);
            int dz = distanceToRange(z, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
            value += getBeardContributionSameY(dx, y - plan.pieceGroundY[p], dz) * 0.8D;
        }
        active = scratch.boxPieces;
        for (int a = 0; a < scratch.boxCount; a++) {
            int p = active[a];
            int dx = distanceToRange(x, plan.pieceMinX[p], plan.pieceMaxX[p]);
            int yDistance = distanceToRange(y, plan.pieceGroundY[p], plan.pieceMaxY[p]);
            int dz = distanceToRange(z, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
            value += getBeardContributionUnchecked(dx, yDistance, dz, y - plan.pieceGroundY[p]) * 0.8D;
        }
        active = scratch.encapsulatePieces;
        for (int a = 0; a < scratch.encapsulateCount; a++) {
            int p = active[a];
            int dx = distanceToRange(x, plan.pieceMinX[p], plan.pieceMaxX[p]);
            int dy = distanceToRange(y, plan.pieceMinY[p], plan.pieceMaxY[p]);
            int dz = distanceToRange(z, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
            value += getEncapsulateContributionScaled(dx, dy, dz);
        }
        active = scratch.junctions;
        for (int a = 0; a < scratch.junctionCount; a++) {
            int j = active[a];
            value += getBeardContributionSameY(x - plan.junctionX[j], y - plan.junctionY[j], z - plan.junctionZ[j]) * 0.4D;
        }
        return value;
    }

    public static void fillCell(
            GABeardifierPlan plan,
            GABeardifierCellScratch scratch,
            double[] out,
            int cellWidth,
            int cellHeight,
            int startX,
            int startY,
            int startZ
    ) {
        int cellValues = plan.cellValueCount(cellWidth, cellHeight);
        Arrays.fill(out, 0, cellValues, 0.0D);
        accumulateCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);
    }

    public static void accumulateCell(
            GABeardifierPlan plan,
            GABeardifierCellScratch scratch,
            double[] out,
            int cellWidth,
            int cellHeight,
            int startX,
            int startY,
            int startZ
    ) {
        if (plan == null) {
            return;
        }
        int maxX = startX + cellWidth - 1;
        int maxY = startY + cellHeight - 1;
        int maxZ = startZ + cellWidth - 1;
        if (plan.outside(startX, maxX, startY, maxY, startZ, maxZ)) {
            return;
        }
        plan.collectActive(scratch, startX, maxX, startY, maxY, startZ, maxZ);
        if (scratch.empty()) {
            return;
        }
        applyBuryCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);
        applyThinCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);
        applyBoxCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);
        applyEncapsulateCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);
        applyJunctionCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);
    }

    public static double getBuryContribution(double x, double y, double z) {
        double distanceSquared = x * x + y * y + z * z;
        if (distanceSquared > 36.0D) {
            return 0.0D;
        }
        return 1.0D - Math.sqrt(distanceSquared) * 0.16666666666666666D;
    }

    private static double getBuryContributionHalfY(int xDistance, int yOffset, int zDistance) {
        if (xDistance < 0 || xDistance >= 6 || zDistance < 0 || zDistance >= 6 || yOffset < -11 || yOffset > 11) {
            return 0.0D;
        }
        int index = ((xDistance * 23) + (yOffset + 11)) * 6 + zDistance;
        return BURY_HALF_Y[index];
    }

    private static double getEncapsulateContribution(int xDistance, int yDistance, int zDistance) {
        if ((xDistance | yDistance | zDistance) < 0 || xDistance >= 12 || yDistance >= 12 || zDistance >= 12) {
            return 0.0D;
        }
        int index = ((xDistance * 12) + yDistance) * 12 + zDistance;
        return ENCAPSULATE_HALF[index];
    }

    private static double getEncapsulateContributionScaled(int xDistance, int yDistance, int zDistance) {
        if ((xDistance | yDistance | zDistance) < 0 || xDistance >= 12 || yDistance >= 12 || zDistance >= 12) {
            return 0.0D;
        }
        int index = ((xDistance * 12) + yDistance) * 12 + zDistance;
        return ENCAPSULATE_HALF_0_8[index];
    }

    private static double[] initBuryHalfYTable() {
        double[] table = new double[6 * 23 * 6];
        int index = 0;
        for (int x = 0; x <= 5; x++) {
            for (int yOffset = -11; yOffset <= 11; yOffset++) {
                for (int z = 0; z <= 5; z++) {
                    table[index++] = getBuryContribution(x, (double) yOffset * 0.5D, z);
                }
            }
        }
        return table;
    }

    private static double[] initEncapsulateHalfTable() {
        double[] table = new double[12 * 12 * 12];
        int index = 0;
        for (int x = 0; x <= 11; x++) {
            for (int y = 0; y <= 11; y++) {
                for (int z = 0; z <= 11; z++) {
                    table[index++] = getBuryContribution((double) x * 0.5D, (double) y * 0.5D, (double) z * 0.5D);
                }
            }
        }
        return table;
    }

    private static double[] initScaledTable(double[] source, double scale) {
        double[] table = new double[source.length];
        for (int i = 0; i < source.length; i++) {
            table[i] = source[i] * scale;
        }
        return table;
    }

    private static double computeJunctionAt(GABeardifierPlan plan, int x, int y, int z) {
        double value = 0.0D;
        int[] junctionX = plan.junctionX;
        for (int j = 0; j < junctionX.length; j++) {
            int dx = x - junctionX[j];
            int dy = y - plan.junctionY[j];
            int dz = z - plan.junctionZ[j];
            if (inKernelRange(dx) && inKernelRange(dy) && inKernelRange(dz)) {
                value += getBeardContributionSameY(dx, dy, dz) * 0.4D;
            }
        }
        return value;
    }

    private static void applyBuryCell(
            GABeardifierPlan plan,
            GABeardifierCellScratch scratch,
            double[] out,
            int cellWidth,
            int cellHeight,
            int startX,
            int startY,
            int startZ
    ) {
        int[] active = scratch.buryPieces;
        for (int a = 0; a < scratch.buryCount; a++) {
            int p = active[a];
            int lx0 = clamp(plan.pieceInfluenceMinX[p] - startX, 0, cellWidth - 1);
            int lx1 = clamp(plan.pieceInfluenceMaxX[p] - startX, 0, cellWidth - 1);
            int ly0 = clamp(plan.pieceInfluenceMinY[p] - startY, 0, cellHeight - 1);
            int ly1 = clamp(plan.pieceInfluenceMaxY[p] - startY, 0, cellHeight - 1);
            int lz0 = clamp(plan.pieceInfluenceMinZ[p] - startZ, 0, cellWidth - 1);
            int lz1 = clamp(plan.pieceInfluenceMaxZ[p] - startZ, 0, cellWidth - 1);
            for (int lx = lx0; lx <= lx1; lx++) {
                int blockX = startX + lx;
                int dx = distanceToRange(blockX, plan.pieceMinX[p], plan.pieceMaxX[p]);
                for (int lz = lz0; lz <= lz1; lz++) {
                    int blockZ = startZ + lz;
                    int dz = distanceToRange(blockZ, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
                    int base = ((lx * cellWidth) + lz) * cellHeight;
                    for (int ly = ly1; ly >= ly0; ly--) {
                        int blockY = startY + ly;
                        int idx = base + (cellHeight - 1 - ly);
                        out[idx] += getBuryContributionHalfY(dx, blockY - plan.pieceGroundY[p], dz);
                    }
                }
            }
        }
    }

    private static void applyThinCell(
            GABeardifierPlan plan,
            GABeardifierCellScratch scratch,
            double[] out,
            int cellWidth,
            int cellHeight,
            int startX,
            int startY,
            int startZ
    ) {
        int[] active = scratch.thinPieces;
        for (int a = 0; a < scratch.thinCount; a++) {
            int p = active[a];
            int lx0 = clamp(plan.pieceInfluenceMinX[p] - startX, 0, cellWidth - 1);
            int lx1 = clamp(plan.pieceInfluenceMaxX[p] - startX, 0, cellWidth - 1);
            int ly0 = clamp(plan.pieceInfluenceMinY[p] - startY, 0, cellHeight - 1);
            int ly1 = clamp(plan.pieceInfluenceMaxY[p] - startY, 0, cellHeight - 1);
            int lz0 = clamp(plan.pieceInfluenceMinZ[p] - startZ, 0, cellWidth - 1);
            int lz1 = clamp(plan.pieceInfluenceMaxZ[p] - startZ, 0, cellWidth - 1);
            for (int lx = lx0; lx <= lx1; lx++) {
                int blockX = startX + lx;
                int dx = distanceToRange(blockX, plan.pieceMinX[p], plan.pieceMaxX[p]);
                for (int lz = lz0; lz <= lz1; lz++) {
                    int blockZ = startZ + lz;
                    int dz = distanceToRange(blockZ, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
                    int base = ((lx * cellWidth) + lz) * cellHeight;
                    for (int ly = ly1; ly >= ly0; ly--) {
                        int dy = startY + ly - plan.pieceGroundY[p];
                        int idx = base + (cellHeight - 1 - ly);
                        out[idx] += getBeardContributionSameY(dx, dy, dz) * 0.8D;
                    }
                }
            }
        }
    }

    private static void applyBoxCell(
            GABeardifierPlan plan,
            GABeardifierCellScratch scratch,
            double[] out,
            int cellWidth,
            int cellHeight,
            int startX,
            int startY,
            int startZ
    ) {
        int[] active = scratch.boxPieces;
        for (int a = 0; a < scratch.boxCount; a++) {
            int p = active[a];
            int lx0 = clamp(plan.pieceInfluenceMinX[p] - startX, 0, cellWidth - 1);
            int lx1 = clamp(plan.pieceInfluenceMaxX[p] - startX, 0, cellWidth - 1);
            int ly0 = clamp(plan.pieceInfluenceMinY[p] - startY, 0, cellHeight - 1);
            int ly1 = clamp(plan.pieceInfluenceMaxY[p] - startY, 0, cellHeight - 1);
            int lz0 = clamp(plan.pieceInfluenceMinZ[p] - startZ, 0, cellWidth - 1);
            int lz1 = clamp(plan.pieceInfluenceMaxZ[p] - startZ, 0, cellWidth - 1);
            for (int lx = lx0; lx <= lx1; lx++) {
                int blockX = startX + lx;
                int dx = distanceToRange(blockX, plan.pieceMinX[p], plan.pieceMaxX[p]);
                for (int lz = lz0; lz <= lz1; lz++) {
                    int blockZ = startZ + lz;
                    int dz = distanceToRange(blockZ, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
                    int base = ((lx * cellWidth) + lz) * cellHeight;
                    for (int ly = ly1; ly >= ly0; ly--) {
                        int blockY = startY + ly;
                        int yDistance = distanceToRange(blockY, plan.pieceGroundY[p], plan.pieceMaxY[p]);
                        int idx = base + (cellHeight - 1 - ly);
                        out[idx] += getBeardContributionUnchecked(dx, yDistance, dz, blockY - plan.pieceGroundY[p]) * 0.8D;
                    }
                }
            }
        }
    }

    private static void applyEncapsulateCell(
            GABeardifierPlan plan,
            GABeardifierCellScratch scratch,
            double[] out,
            int cellWidth,
            int cellHeight,
            int startX,
            int startY,
            int startZ
    ) {
        int[] active = scratch.encapsulatePieces;
        for (int a = 0; a < scratch.encapsulateCount; a++) {
            int p = active[a];
            int lx0 = clamp(plan.pieceInfluenceMinX[p] - startX, 0, cellWidth - 1);
            int lx1 = clamp(plan.pieceInfluenceMaxX[p] - startX, 0, cellWidth - 1);
            int ly0 = clamp(plan.pieceInfluenceMinY[p] - startY, 0, cellHeight - 1);
            int ly1 = clamp(plan.pieceInfluenceMaxY[p] - startY, 0, cellHeight - 1);
            int lz0 = clamp(plan.pieceInfluenceMinZ[p] - startZ, 0, cellWidth - 1);
            int lz1 = clamp(plan.pieceInfluenceMaxZ[p] - startZ, 0, cellWidth - 1);
            for (int lx = lx0; lx <= lx1; lx++) {
                int blockX = startX + lx;
                int dx = distanceToRange(blockX, plan.pieceMinX[p], plan.pieceMaxX[p]);
                for (int lz = lz0; lz <= lz1; lz++) {
                    int blockZ = startZ + lz;
                    int dz = distanceToRange(blockZ, plan.pieceMinZ[p], plan.pieceMaxZ[p]);
                    int base = ((lx * cellWidth) + lz) * cellHeight;
                    for (int ly = ly1; ly >= ly0; ly--) {
                        int blockY = startY + ly;
                        int dy = distanceToRange(blockY, plan.pieceMinY[p], plan.pieceMaxY[p]);
                        int idx = base + (cellHeight - 1 - ly);
                        out[idx] += getEncapsulateContributionScaled(dx, dy, dz);
                    }
                }
            }
        }
    }

    private static void applyJunctionCell(
            GABeardifierPlan plan,
            GABeardifierCellScratch scratch,
            double[] out,
            int cellWidth,
            int cellHeight,
            int startX,
            int startY,
            int startZ
    ) {
        int[] active = scratch.junctions;
        int[] junctionX = plan.junctionX;
        int[] junctionY = plan.junctionY;
        int[] junctionZ = plan.junctionZ;
        for (int a = 0; a < scratch.junctionCount; a++) {
            int j = active[a];
            int lx0 = clamp(junctionX[j] - 12 - startX, 0, cellWidth - 1);
            int lx1 = clamp(junctionX[j] + 11 - startX, 0, cellWidth - 1);
            int ly0 = clamp(junctionY[j] - 12 - startY, 0, cellHeight - 1);
            int ly1 = clamp(junctionY[j] + 11 - startY, 0, cellHeight - 1);
            int lz0 = clamp(junctionZ[j] - 12 - startZ, 0, cellWidth - 1);
            int lz1 = clamp(junctionZ[j] + 11 - startZ, 0, cellWidth - 1);
            for (int lx = lx0; lx <= lx1; lx++) {
                int dx = startX + lx - junctionX[j];
                for (int lz = lz0; lz <= lz1; lz++) {
                    int dz = startZ + lz - junctionZ[j];
                    int base = ((lx * cellWidth) + lz) * cellHeight;
                    for (int ly = ly1; ly >= ly0; ly--) {
                        int dy = startY + ly - junctionY[j];
                        int idx = base + (cellHeight - 1 - ly);
                        out[idx] += getBeardContributionSameY(dx, dy, dz) * 0.4D;
                    }
                }
            }
        }
    }

    private static boolean outsidePiece(GABeardifierPlan plan, int index, int x, int y, int z) {
        return x < plan.pieceInfluenceMinX[index]
                || x > plan.pieceInfluenceMaxX[index]
                || y < plan.pieceInfluenceMinY[index]
                || y > plan.pieceInfluenceMaxY[index]
                || z < plan.pieceInfluenceMinZ[index]
                || z > plan.pieceInfluenceMaxZ[index];
    }

    private static int distanceToRange(int value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0;
    }

    private static boolean inKernelRange(int value) {
        return value >= -12 && value < 12;
    }

    private static double getBeardContributionUnchecked(int xDistance, int yDistance, int zDistance, int yOffset) {
        int xIndex = xDistance + 12;
        int yIndex = yDistance + 12;
        int zIndex = zDistance + 12;
        if (outsideKernelIndex(xIndex, yIndex, zIndex)) {
            return 0.0D;
        }
        float[] kernel = requireKernel();
        double y = (double) yOffset + 0.5D;
        double lengthSquared = (double) xDistance * (double) xDistance + y * y + (double) zDistance * (double) zDistance;
        double contribution = -y * fastInvSqrt(lengthSquared * 0.5D) * 0.5D;
        int index = ((zIndex * 24) + xIndex) * 24 + yIndex;
        return contribution * (double) kernel[index];
    }

    private static double getBeardContributionSameY(int x, int y, int z) {
        int xIndex = x + 12;
        int yIndex = y + 12;
        int zIndex = z + 12;
        if (outsideKernelIndex(xIndex, yIndex, zIndex)) {
            return 0.0D;
        }
        float[] table = beardSameY;
        if (table == null) {
            table = initBeardSameYTable();
        }
        int index = ((zIndex * 24) + xIndex) * 24 + yIndex;
        return table[index];
    }

    private static boolean outsideKernelIndex(int xIndex, int yIndex, int zIndex) {
        return (xIndex | yIndex | zIndex) < 0 || xIndex >= 24 || yIndex >= 24 || zIndex >= 24;
    }

    private static float[] initBeardSameYTable() {
        synchronized (GABeardifierKernel.class) {
            float[] table = beardSameY;
            if (table != null) {
                return table;
            }
            float[] kernel = requireKernel();
            table = new float[24 * 24 * 24];
            for (int z = -12; z < 12; z++) {
                for (int x = -12; x < 12; x++) {
                    for (int yOffset = -12; yOffset < 12; yOffset++) {
                        double y = (double) yOffset + 0.5D;
                        double lengthSquared = (double) x * (double) x + y * y + (double) z * (double) z;
                        double contribution = -y * fastInvSqrt(lengthSquared * 0.5D) * 0.5D;
                        int index = ((z + 12) * 24 + (x + 12)) * 24 + (yOffset + 12);
                        table[index] = (float) (contribution * (double) kernel[index]);
                    }
                }
            }
            beardSameY = table;
            return table;
        }
    }

    private static float[] requireKernel() {
        float[] kernel = beardKernel;
        if (kernel == null) {
            throw new IllegalStateException("beard kernel is not initialized");
        }
        return kernel;
    }

    private static double fastInvSqrt(double value) {
        double half = 0.5D * value;
        long bits = Double.doubleToRawLongBits(value);
        bits = 0x5fe6eb50c7b537a9L - (bits >> 1);
        value = Double.longBitsToDouble(bits);
        return value * (1.5D - half * value * value);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
