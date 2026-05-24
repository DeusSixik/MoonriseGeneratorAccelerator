package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierColumnMath {

    private GABeardifierColumnMath() {
    }

    public static int axisDistance(int block, int min, int max) {
        return Math.max(0, Math.max(min - block, block - max));
    }

    public static void fillPieceDistances(
            int blockX,
            int blockZ,
            int[] activePieces,
            int activePieceCount,
            int[] minX,
            int[] maxX,
            int[] minZ,
            int[] maxZ,
            int[] outDx,
            int[] outDz
    ) {
        for (int activeIndex = 0; activeIndex < activePieceCount; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            outDx[activeIndex] = axisDistance(blockX, minX[pieceIndex], maxX[pieceIndex]);
            outDz[activeIndex] = axisDistance(blockZ, minZ[pieceIndex], maxZ[pieceIndex]);
        }
    }

    public static void fillJunctionOffsets(
            int blockX,
            int blockZ,
            int[] activeJunctions,
            int activeJunctionCount,
            int[] junctionX,
            int[] junctionZ,
            int[] outDx,
            int[] outDz
    ) {
        for (int activeIndex = 0; activeIndex < activeJunctionCount; activeIndex++) {
            int junctionIndex = activeJunctions[activeIndex];
            outDx[activeIndex] = blockX - junctionX[junctionIndex];
            outDz[activeIndex] = blockZ - junctionZ[junctionIndex];
        }
    }
}
