package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACommitOrderKeyTest {
    @Test
    void comparatorIsDeterministicAcrossChunksAndUnits() {
        List<GACommitOrderKey> keys = List.of(
                new GACommitOrderKey(1, 0, 2, 0, 2, 0, 0, 0L),
                new GACommitOrderKey(0, 3, 5, 5, 5, 5, 9, 1L),
                new GACommitOrderKey(0, 3, 5, 4, 5, 4, 9, 1L),
                new GACommitOrderKey(0, 3, 5, 4, 5, 4, 8, 99L),
                new GACommitOrderKey(0, 2, 9, 9, 9, 9, 0, 0L)
        );
        List<GACommitOrderKey> shuffledA = new ArrayList<>(keys);
        List<GACommitOrderKey> shuffledB = new ArrayList<>(keys);
        Collections.shuffle(shuffledA, new java.util.Random(7L));
        Collections.shuffle(shuffledB, new java.util.Random(93L));

        shuffledA.sort(GACommitOrderKey.COMPARATOR);
        shuffledB.sort(GACommitOrderKey.COMPARATOR);

        assertEquals(shuffledA, shuffledB);
        assertEquals(keys.get(4), shuffledA.get(0));
        assertEquals(keys.get(0), shuffledA.get(shuffledA.size() - 1));
    }

    @Test
    void comparatorIsTransitive() {
        GACommitOrderKey a = new GACommitOrderKey(0, 0, 0, 0, 0, 0, 0, 0L);
        GACommitOrderKey b = new GACommitOrderKey(0, 0, 0, 0, 0, 0, 1, 0L);
        GACommitOrderKey c = new GACommitOrderKey(0, 0, 0, 0, 0, 0, 1, 1L);

        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(c) < 0);
        assertTrue(a.compareTo(c) < 0);
    }
}
