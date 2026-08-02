package dev.sixik.generator_accelerator.common.noise;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FastVectorNoiseKernelParityTest {

    @Test
    void wrapMatchesVanillaPerlinWrap() {
        double[] values = {
                0.0,
                -0.0,
                1.0,
                -1.0,
                33554432.0,
                -33554432.0,
                33554432.0 + 0.25,
                -33554432.0 - 0.25,
                30_000_000.125,
                -30_000_000.125,
                1.23456789012345E12,
                -1.23456789012345E12
        };

        for (double value : values) {
            assertSameBits(PerlinNoise.wrap(value), FastVectorNoise.wrap(value));
        }
    }

    @Test
    void scalarSampleMatchesVanillaImprovedNoise() {
        ImprovedNoise noise = new ImprovedNoise(RandomSource.create(0x5EED1234ABCDEFL));
        double[][] coords = {
                {0.0, 0.0, 0.0},
                {1.25, 63.5, -9.75},
                {-1.0, -2.0, -3.0},
                {-1.0000001, -64.25, 255.75},
                {30_000_000.125, -48.5, -30_000_000.875},
                {33_554_432.25, 128.125, -33_554_432.5}
        };
        double[][] ySettings = {
                {0.0, 0.0},
                {0.25, 0.0},
                {0.5, 0.125},
                {684.412, 684.412}
        };

        for (double[] coord : coords) {
            assertSameBits(noise.noise(coord[0], coord[1], coord[2]),
                    FastVectorNoise.sample(noise, coord[0], coord[1], coord[2]));
            for (double[] ySetting : ySettings) {
                assertSameBits(noise.noise(coord[0], coord[1], coord[2], ySetting[0], ySetting[1]),
                        FastVectorNoise.sample(noise, coord[0], coord[1], coord[2], ySetting[0], ySetting[1]));
            }
        }
    }

    @Test
    void wrappedColumnMatchesRepeatedVanillaSamples() {
        ImprovedNoise noise = new ImprovedNoise(RandomSource.create(987654321L));
        FastVectorNoise kernel = new FastVectorNoise(noise.p, noise.xo, noise.yo, noise.zo);
        int x = -12345;
        int z = 6789;
        int yStart = -72;
        int count = 33;
        double scaleX = 0.03125;
        double scaleY = 0.0625;
        double scaleZ = 0.015625;
        double amplitude = -0.375;
        double[] actual = new double[count];

        kernel.fillWrappedColumn(actual, x, z, yStart, count, scaleX, scaleY, scaleZ, amplitude);

        double wrappedX = PerlinNoise.wrap((double) x * scaleX);
        double wrappedZ = PerlinNoise.wrap((double) z * scaleZ);
        for (int i = 0; i < count; i++) {
            double wrappedY = PerlinNoise.wrap((double) (yStart + i) * scaleY);
            double expected = noise.noise(wrappedX, wrappedY, wrappedZ) * amplitude;
            assertSameBits(expected, actual[i]);
        }
    }

    private static void assertSameBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual),
                () -> "expected=" + expected + ", actual=" + actual);
    }
}
