package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierActivePieces {

    private GABeardifierActivePieces() {
    }

    public static void groupByTerrain(
            int[] activePieces,
            int activePieceCount,
            byte[] terrain,
            int[] scratch,
            int buryKind,
            int thinKind,
            int boxKind,
            int encapsulateKind,
            int[] countsOut
    ) {
        int buryCount = 0;
        int thinCount = 0;
        int boxCount = 0;
        int encapsulateCount = 0;
        for (int activeIndex = 0; activeIndex < activePieceCount; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int terrainKind = terrain[pieceIndex] & 0xFF;
            if (terrainKind == buryKind) {
                buryCount++;
            } else if (terrainKind == thinKind) {
                thinCount++;
            } else if (terrainKind == boxKind) {
                boxCount++;
            } else if (terrainKind == encapsulateKind) {
                encapsulateCount++;
            }
        }

        int buryPos = 0;
        int thinPos = buryCount;
        int boxPos = thinPos + thinCount;
        int encapsulatePos = boxPos + boxCount;
        for (int activeIndex = 0; activeIndex < activePieceCount; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int terrainKind = terrain[pieceIndex] & 0xFF;
            if (terrainKind == buryKind) {
                scratch[buryPos++] = pieceIndex;
            } else if (terrainKind == thinKind) {
                scratch[thinPos++] = pieceIndex;
            } else if (terrainKind == boxKind) {
                scratch[boxPos++] = pieceIndex;
            } else if (terrainKind == encapsulateKind) {
                scratch[encapsulatePos++] = pieceIndex;
            }
        }

        System.arraycopy(scratch, 0, activePieces, 0, activePieceCount);
        countsOut[0] = buryCount;
        countsOut[1] = thinCount;
        countsOut[2] = boxCount;
        countsOut[3] = encapsulateCount;
    }
}
