package dev.sixik.generator_accelerator.common.aquifer.region;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GARegionalAquiferSampleCacheTest {
    private static final Object EROSION_KEY = new Object();
    private static final Object DEPTH_KEY = new Object();
    private static final Object FLOODEDNESS_KEY = new Object();

    @BeforeEach
    void clearCache() {
        GARegionalAquiferSampleCache.clearForTests();
    }

    @Test
    void reusesExactSampleWithinRegion() {
        GARegionalAquiferCacheOwner owner = new GARegionalAquiferCacheOwner(
                new StubPositionalRandomFactory(),
                (x, y, z) -> null,
                EROSION_KEY,
                DEPTH_KEY,
                FLOODEDNESS_KEY
        );
        GARegionalAquiferSampleCache.Sample sample = new GARegionalAquiferSampleCache.Sample(48, (byte) 2, 99);

        assertNull(GARegionalAquiferSampleCache.get(owner, 12, 34, 18));
        GARegionalAquiferSampleCache.putIfAbsent(owner, 12, 34, 18, sample);

        assertEquals(sample, GARegionalAquiferSampleCache.get(owner, 12, 34, 18));
        assertEquals(1, GARegionalAquiferSampleCache.regionCountForTests());
    }

    @Test
    void separatesDifferentRegionsAndOwners() {
        GARegionalAquiferCacheOwner ownerA = new GARegionalAquiferCacheOwner(
                new StubPositionalRandomFactory(),
                (x, y, z) -> null,
                EROSION_KEY,
                DEPTH_KEY,
                FLOODEDNESS_KEY
        );
        GARegionalAquiferCacheOwner ownerB = new GARegionalAquiferCacheOwner(
                new StubPositionalRandomFactory(),
                (x, y, z) -> null,
                new Object(),
                new Object(),
                new Object()
        );

        GARegionalAquiferSampleCache.putIfAbsent(ownerA, 0, 10, 0, new GARegionalAquiferSampleCache.Sample(32, (byte) 1, 5));
        GARegionalAquiferSampleCache.putIfAbsent(ownerA, 80, 10, 0, new GARegionalAquiferSampleCache.Sample(16, (byte) 0, 7));
        GARegionalAquiferSampleCache.putIfAbsent(ownerB, 0, 10, 0, new GARegionalAquiferSampleCache.Sample(8, (byte) 3, 11));

        assertEquals(5, GARegionalAquiferSampleCache.get(ownerA, 0, 10, 0).blockId());
        assertEquals(7, GARegionalAquiferSampleCache.get(ownerA, 80, 10, 0).blockId());
        assertEquals(11, GARegionalAquiferSampleCache.get(ownerB, 0, 10, 0).blockId());
        assertEquals(3, GARegionalAquiferSampleCache.regionCountForTests());
    }

    private static final class StubPositionalRandomFactory implements PositionalRandomFactory {
        @Override
        public RandomSource at(int i, int j, int k) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RandomSource fromHashOf(ResourceLocation resourceLocation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RandomSource fromHashOf(String string) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RandomSource fromSeed(long l) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void parityConfigString(StringBuilder stringBuilder) {
            stringBuilder.append("stub");
        }
    }
}
