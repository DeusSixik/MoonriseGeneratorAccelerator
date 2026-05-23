package dev.sixik.generator_accelerator.common.beardifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class GABeardifierActivePiecesTest {

    @Test
    void groupByTerrainReordersActivePiecesIntoHotLoopBuckets() {
        int[] activePieces = {4, 0, 3, 1, 2};
        byte[] terrain = {3, 4, 2, 1, 3};
        int[] scratch = new int[activePieces.length];
        int[] counts = new int[4];

        GABeardifierActivePieces.groupByTerrain(activePieces, activePieces.length, terrain, scratch, 1, 2, 3, 4, counts);

        assertArrayEquals(new int[]{3, 2, 4, 0, 1}, activePieces);
        assertArrayEquals(new int[]{1, 1, 2, 1}, counts);
    }
}
