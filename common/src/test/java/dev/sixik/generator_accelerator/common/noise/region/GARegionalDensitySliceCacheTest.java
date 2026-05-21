package dev.sixik.generator_accelerator.common.noise.region;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GARegionalDensitySliceCacheTest {
    static {
        System.setProperty("ga.noise.regionalDensitySliceCache.enabled", "true");
    }

    @BeforeEach
    void clearCache() {
        GARegionalDensitySliceCache.clearForTests();
    }

    @Test
    void reusesExactSlicePerOwnerRegionAndLocalSlice() {
        GARegionalDensitySliceCacheOwner owner = owner();
        AtomicInteger builds = new AtomicInteger();

        double[] first = GARegionalDensitySliceCache.sliceValues(owner, 2, -3, 5, () -> {
            builds.incrementAndGet();
            return new double[]{1.0D, 2.0D, 3.0D};
        });
        double[] second = GARegionalDensitySliceCache.sliceValues(owner, 2, -3, 5, () -> {
            builds.incrementAndGet();
            return new double[]{9.0D};
        });

        assertSame(first, second);
        assertArrayEquals(new double[]{1.0D, 2.0D, 3.0D}, second);
        assertEquals(1, builds.get());
        assertEquals(1, GARegionalDensitySliceCache.cacheSizeForTests());
    }

    @Test
    void separatesDifferentOwnersAndSlices() {
        GARegionalDensitySliceCacheOwner ownerA = owner();
        GARegionalDensitySliceCacheOwner ownerB = new GARegionalDensitySliceCacheOwner(
                new Object(),
                new Object(),
                4,
                8,
                4,
                48,
                -8,
                new Object[]{new Object(), new Object()}
        );

        double[] a0 = GARegionalDensitySliceCache.sliceValues(ownerA, 0, 0, 0, () -> new double[]{4.0D});
        double[] a1 = GARegionalDensitySliceCache.sliceValues(ownerA, 0, 0, 1, () -> new double[]{5.0D});
        double[] b0 = GARegionalDensitySliceCache.sliceValues(ownerB, 0, 0, 0, () -> new double[]{6.0D});

        assertArrayEquals(new double[]{4.0D}, a0);
        assertArrayEquals(new double[]{5.0D}, a1);
        assertArrayEquals(new double[]{6.0D}, b0);
        assertEquals(3, GARegionalDensitySliceCache.cacheSizeForTests());
    }

    private static GARegionalDensitySliceCacheOwner owner() {
        Object blender = new Object();
        Object settings = new Object();
        Object[] keys = new Object[]{new Object(), new Object()};
        return new GARegionalDensitySliceCacheOwner(blender, settings, 4, 8, 4, 48, -8, keys);
    }
}
