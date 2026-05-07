package dev.sixik.generator_accelerator.common.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BiomeDecorationScratchTest {
    @Test
    void collectsSortedFeatureIndicesFromUnionedMasks() {
        BiomeDecorationScratch scratch = new BiomeDecorationScratch();

        scratch.beginStep(130);
        scratch.addFeatureMask(new long[] {
                (1L << 3) | (1L << 9),
                (1L << 2)
        });
        scratch.addFeatureMask(new long[] {
                (1L << 9) | (1L << 12),
                (1L << 1),
                (1L << 1)
        });

        int[] indices = scratch.collectFeatureIndices();

        assertEquals(6, scratch.featureIndexCount());
        assertArrayEquals(new int[] {3, 9, 12, 65, 66, 129}, java.util.Arrays.copyOf(indices, scratch.featureIndexCount()));
    }
}
