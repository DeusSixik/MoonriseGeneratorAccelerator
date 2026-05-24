package dev.sixik.generator_accelerator.common.beardifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GABeardifierColumnMathTest {

    @Test
    void axisDistanceMatchesIntervalSemantics() {
        assertEquals(0, GABeardifierColumnMath.axisDistance(8, 4, 12));
        assertEquals(0, GABeardifierColumnMath.axisDistance(4, 4, 12));
        assertEquals(0, GABeardifierColumnMath.axisDistance(12, 4, 12));
        assertEquals(3, GABeardifierColumnMath.axisDistance(1, 4, 12));
        assertEquals(5, GABeardifierColumnMath.axisDistance(17, 4, 12));
    }

    @Test
    void fillPieceDistancesUsesActiveOrder() {
        int[] activePieces = {2, 0};
        int[] minX = {10, 30, -6};
        int[] maxX = {12, 40, -2};
        int[] minZ = {-4, 7, 20};
        int[] maxZ = {1, 9, 25};
        int[] dx = new int[activePieces.length];
        int[] dz = new int[activePieces.length];

        GABeardifierColumnMath.fillPieceDistances(15, 18, activePieces, activePieces.length, minX, maxX, minZ, maxZ, dx, dz);

        assertArrayEquals(new int[]{17, 3}, dx);
        assertArrayEquals(new int[]{2, 17}, dz);
    }

    @Test
    void fillJunctionOffsetsKeepsSignedOffsets() {
        int[] activeJunctions = {1, 0};
        int[] junctionX = {11, -4};
        int[] junctionZ = {-8, 5};
        int[] dx = new int[activeJunctions.length];
        int[] dz = new int[activeJunctions.length];

        GABeardifierColumnMath.fillJunctionOffsets(6, 2, activeJunctions, activeJunctions.length, junctionX, junctionZ, dx, dz);

        assertArrayEquals(new int[]{10, -5}, dx);
        assertArrayEquals(new int[]{-3, 10}, dz);
    }
}
