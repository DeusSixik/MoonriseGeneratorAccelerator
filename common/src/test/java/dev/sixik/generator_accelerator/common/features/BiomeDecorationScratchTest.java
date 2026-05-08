package dev.sixik.generator_accelerator.common.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeDecorationScratchTest {

    @Test
    void tracksOnlyStepsThatActuallyReceivedFeatureBits() {
        BiomeDecorationScratch scratch = new BiomeDecorationScratch();
        scratch.beginCombinedFeatureMasks(3, new int[]{1, 1, 1});

        scratch.addBiomeFeatureMasks(
                new long[][]{
                        {0L},
                        {1L << 5},
                        {0L}
                },
                new int[]{1, 1, 1}
        );

        assertFalse(scratch.stepHasFeatures(0));
        assertTrue(scratch.stepHasFeatures(1));
        assertFalse(scratch.stepHasFeatures(2));

        scratch.clearBiomeFeatureMasks();

        assertFalse(scratch.stepHasFeatures(0));
        assertFalse(scratch.stepHasFeatures(1));
        assertFalse(scratch.stepHasFeatures(2));
    }

    @Test
    void mergesSparseBiomeFeatureDataWithoutScanningEmptySteps() {
        BiomeDecorationScratch scratch = new BiomeDecorationScratch();
        scratch.beginCombinedFeatureMasks(4, new int[]{1, 1, 1, 1});

        scratch.addBiomeFeatureData(
                new StepFeatureCache.BiomeFeatureData(
                        new long[][]{
                                {0L},
                                {1L << 3},
                                {0L},
                                {1L << 7}
                        },
                        new int[]{1, 3},
                        (1L << 1) | (1L << 3)
                ),
                new int[]{1, 1, 1, 1}
        );

        assertFalse(scratch.stepHasFeatures(0));
        assertTrue(scratch.stepHasFeatures(1));
        assertFalse(scratch.stepHasFeatures(2));
        assertTrue(scratch.stepHasFeatures(3));
    }
}
