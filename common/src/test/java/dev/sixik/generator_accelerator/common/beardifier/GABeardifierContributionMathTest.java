package dev.sixik.generator_accelerator.common.beardifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GABeardifierContributionMathTest {

    @Test
    void buryMatchesExpectedDistanceFalloff() {
        assertEquals(0.5D, GABeardifierContributionMath.bury(2, 4, 1), 1.0E-12D);
        assertEquals(0.0D, GABeardifierContributionMath.bury(8, 0, 0), 1.0E-12D);
    }

    @Test
    void yDistanceHelpersMatchIntervalSemantics() {
        assertEquals(0, GABeardifierContributionMath.boxYDistance(10, 4, 20));
        assertEquals(3, GABeardifierContributionMath.boxYDistance(1, 4, 20));
        assertEquals(5, GABeardifierContributionMath.boxYDistance(25, 4, 20));

        assertEquals(0, GABeardifierContributionMath.encapsulateYDistance(10, 4, 20));
        assertEquals(3, GABeardifierContributionMath.encapsulateYDistance(1, 4, 20));
        assertEquals(5, GABeardifierContributionMath.encapsulateYDistance(25, 4, 20));
    }
}
