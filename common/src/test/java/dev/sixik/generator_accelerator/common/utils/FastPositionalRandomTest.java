package dev.sixik.generator_accelerator.common.utils;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FastPositionalRandomTest {
    private static final int[][] COORDS = {
            {0, 0, 0},
            {1, 2, 3},
            {-17, 64, 31},
            {30_000_000, -64, -30_000_000},
            {12345, 255, -98765}
    };

    @Test
    void legacyMatchesVanillaFirstSamples() {
        assertMatchesVanilla(new LegacyRandomSource.LegacyPositionalRandomFactory(123456789L));
    }

    @Test
    void xoroshiroMatchesVanillaFirstSamples() {
        assertMatchesVanilla(new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(0x1234ABCDL, 0x5678EF90L));
        assertMatchesVanilla(new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(0L, 0L));
    }

    private static void assertMatchesVanilla(PositionalRandomFactory factory) {
        int[] bounds = {1, 2, 3, 17, 64, 10_000};
        for (int[] coord : COORDS) {
            int x = coord[0];
            int y = coord[1];
            int z = coord[2];
            assertEquals(factory.at(x, y, z).nextFloat(), FastPositionalRandom.nextFloatAt(factory, x, y, z));
            assertEquals(factory.at(x, y, z).nextDouble(), FastPositionalRandom.nextDoubleAt(factory, x, y, z));
            for (int bound : bounds) {
                assertEquals(factory.at(x, y, z).nextInt(bound), FastPositionalRandom.nextIntAt(factory, x, y, z, bound));
            }
        }
    }
}
