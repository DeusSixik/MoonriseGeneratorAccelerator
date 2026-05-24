package dev.sixik.generator_accelerator.common.beardifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class GABeardifierTerrainRangesTest {

    @Test
    void cumulativeEndsMatchGroupedTerrainOrder() {
        int[] ends = new int[4];

        GABeardifierTerrainRanges.fillEnds(2, 1, 3, 4, ends);

        assertArrayEquals(new int[]{2, 3, 6, 10}, ends);
    }
}
