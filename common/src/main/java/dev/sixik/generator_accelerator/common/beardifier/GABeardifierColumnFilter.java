package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierColumnFilter {

    private GABeardifierColumnFilter() {
    }

    public static int filterPiecesByColumn(
            int[] activePieces,
            int activePieceCount,
            int[] pieceDx,
            int[] pieceDz,
            int[] influenceMinY,
            int[] influenceMaxY,
            int columnMinY,
            int columnMaxY,
            int buryEnd,
            int thinEnd,
            int boxEnd,
            int[] outPieces,
            int[] outDx,
            int[] outDz,
            int[] countsOut
    ) {
        int buryCount = 0;
        int thinCount = 0;
        int boxCount = 0;
        int encapsulateCount = 0;

        for (int activeIndex = 0; activeIndex < activePieceCount; activeIndex++) {
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            int pieceIndex = activePieces[activeIndex];
            if (activeIndex < buryEnd) {
                if (dx > 5 || dz > 5) {
                    continue;
                }
            } else {
                if (dx > 11 || dz > 11
                        || columnMaxY < influenceMinY[pieceIndex]
                        || columnMinY > influenceMaxY[pieceIndex]) {
                    continue;
                }
            }

            int writeIndex = buryCount + thinCount + boxCount + encapsulateCount;
            outPieces[writeIndex] = pieceIndex;
            outDx[writeIndex] = dx;
            outDz[writeIndex] = dz;
            if (activeIndex < buryEnd) {
                buryCount++;
            } else if (activeIndex < thinEnd) {
                thinCount++;
            } else if (activeIndex < boxEnd) {
                boxCount++;
            } else {
                encapsulateCount++;
            }
        }

        countsOut[0] = buryCount;
        countsOut[1] = thinCount;
        countsOut[2] = boxCount;
        countsOut[3] = encapsulateCount;
        return buryCount + thinCount + boxCount + encapsulateCount;
    }

    public static int filterJunctionsByColumn(
            int[] activeJunctions,
            int activeJunctionCount,
            int[] junctionDx,
            int[] junctionDz,
            int[] junctionY,
            int columnMinY,
            int columnMaxY,
            int[] outJunctions,
            int[] outDx,
            int[] outDz
    ) {
        int count = 0;
        for (int activeIndex = 0; activeIndex < activeJunctionCount; activeIndex++) {
            int dx = junctionDx[activeIndex];
            int dz = junctionDz[activeIndex];
            int junctionIndex = activeJunctions[activeIndex];
            if (dx < -12 || dx >= 12
                    || dz < -12 || dz >= 12
                    || columnMaxY < junctionY[junctionIndex] - 12
                    || columnMinY > junctionY[junctionIndex] + 11) {
                continue;
            }
            outJunctions[count] = junctionIndex;
            outDx[count] = dx;
            outDz[count] = dz;
            count++;
        }
        return count;
    }
}
