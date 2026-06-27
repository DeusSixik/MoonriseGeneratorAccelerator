package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierActivePieces {

    private GABeardifierActivePieces() {
    }

    public static void countSortedByTerrain(
            int[] activePieces,
            int activePieceCount,
            int buryEnd,
            int thinEnd,
            int boxEnd,
            int[] countsOut
    ) {
        int buryCount = 0;
        int thinCount = 0;
        int boxCount = 0;
        int encapsulateCount = 0;
        for (int activeIndex = 0; activeIndex < activePieceCount; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            if (pieceIndex < buryEnd) {
                buryCount++;
            } else if (pieceIndex < thinEnd) {
                thinCount++;
            } else if (pieceIndex < boxEnd) {
                boxCount++;
            } else {
                encapsulateCount++;
            }
        }
        countsOut[0] = buryCount;
        countsOut[1] = thinCount;
        countsOut[2] = boxCount;
        countsOut[3] = encapsulateCount;
    }
}
