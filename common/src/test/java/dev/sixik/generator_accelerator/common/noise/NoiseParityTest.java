package dev.sixik.generator_accelerator.common.noise;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoiseParityTest {

    private static final double SIMPLEX_EPSILON = 1.0E-12;
    private static final double IMPROVED_EPSILON = 1.0E-12;
    private static final double DERIVATIVE_EPSILON = 1.0E-10;
    private static final int SAMPLE_COUNT = 2_000;
    private static final long SEED = 0x5EEDC0DEL;

    @Test
    void simplex2dParity() {
        Random random = new Random(SEED);
        int[] permutation = createPermutation(random);

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            double x = nextCoord(random);
            double y = nextCoord(random);

            double expected = simplex2dReference(permutation, x, y);
            double actual = simplex2dOptimized(permutation, x, y);
            assertEquals(expected, actual, SIMPLEX_EPSILON, "Simplex 2D mismatch at sample " + i);
        }
    }

    @Test
    void simplex3dParity() {
        Random random = new Random(SEED ^ 0x9E3779B97F4A7C15L);
        int[] permutation = createPermutation(random);

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            double x = nextCoord(random);
            double y = nextCoord(random);
            double z = nextCoord(random);

            double expected = simplex3dReference(permutation, x, y, z);
            double actual = simplex3dOptimized(permutation, x, y, z);
            assertEquals(expected, actual, SIMPLEX_EPSILON, "Simplex 3D mismatch at sample " + i);
        }
    }

    @Test
    void improvedNoiseParity() {
        Random random = new Random(SEED ^ 0x1234ABCD5678EF90L);
        byte[] permutation = createBytePermutation(random);
        double xo = nextOffset(random);
        double yo = nextOffset(random);
        double zo = nextOffset(random);

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            double x = nextCoord(random);
            double y = nextCoord(random);
            double z = nextCoord(random);
            double yScale = random.nextBoolean() ? 0.0 : nextScale(random);
            double yMax = random.nextBoolean() ? -1.0 : random.nextDouble();

            double expected = improvedNoiseReference(permutation, xo, yo, zo, x, y, z, yScale, yMax);
            double actual = improvedNoiseOptimized(permutation, xo, yo, zo, x, y, z, yScale, yMax);
            assertEquals(expected, actual, IMPROVED_EPSILON, "ImprovedNoise mismatch at sample " + i);
        }
    }

    @Test
    void improvedNoiseDerivativeParity() {
        Random random = new Random(SEED ^ 0xCAFEBABEDEADBEEFL);
        byte[] permutation = createBytePermutation(random);
        double xo = nextOffset(random);
        double yo = nextOffset(random);
        double zo = nextOffset(random);

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            double x = nextCoord(random);
            double y = nextCoord(random);
            double z = nextCoord(random);

            double[] expectedDerivatives = new double[3];
            double[] actualDerivatives = new double[3];

            double expected = improvedNoiseWithDerivativeReference(permutation, xo, yo, zo, x, y, z, expectedDerivatives);
            double actual = improvedNoiseWithDerivativeOptimized(permutation, xo, yo, zo, x, y, z, actualDerivatives);

            assertEquals(expected, actual, IMPROVED_EPSILON, "ImprovedNoise derivative value mismatch at sample " + i);
            assertEquals(expectedDerivatives[0], actualDerivatives[0], DERIVATIVE_EPSILON, "d/dx mismatch at sample " + i);
            assertEquals(expectedDerivatives[1], actualDerivatives[1], DERIVATIVE_EPSILON, "d/dy mismatch at sample " + i);
            assertEquals(expectedDerivatives[2], actualDerivatives[2], DERIVATIVE_EPSILON, "d/dz mismatch at sample " + i);
        }
    }

    private static double nextCoord(Random random) {
        return (random.nextDouble() - 0.5) * 8192.0;
    }

    private static double nextOffset(Random random) {
        return random.nextDouble() * 256.0;
    }

    private static double nextScale(Random random) {
        return 0.01 + random.nextDouble() * 4.0;
    }

    private static int[] createPermutation(Random random) {
        int[] permutation = new int[256];
        for (int i = 0; i < 256; i++) {
            permutation[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int swapIndex = i + random.nextInt(256 - i);
            int tmp = permutation[i];
            permutation[i] = permutation[swapIndex];
            permutation[swapIndex] = tmp;
        }
        return permutation;
    }

    private static byte[] createBytePermutation(Random random) {
        byte[] permutation = new byte[256];
        for (int i = 0; i < 256; i++) {
            permutation[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++) {
            int swapIndex = i + random.nextInt(256 - i);
            byte tmp = permutation[i];
            permutation[i] = permutation[swapIndex];
            permutation[swapIndex] = tmp;
        }
        return permutation;
    }

    private static final int[][] SIMPLEX_GRADIENT = {
            {1, 1, 0},
            {-1, 1, 0},
            {1, -1, 0},
            {-1, -1, 0},
            {1, 0, 1},
            {-1, 0, 1},
            {1, 0, -1},
            {-1, 0, -1},
            {0, 1, 1},
            {0, -1, 1},
            {0, 1, -1},
            {0, -1, -1},
            {1, 1, 0},
            {0, -1, 1},
            {-1, 1, 0},
            {0, -1, -1}
    };

    private static final double[] FLAT_SIMPLEX_GRAD = {
            1.0, 1.0, 0.0,
            -1.0, 1.0, 0.0,
            1.0, -1.0, 0.0,
            -1.0, -1.0, 0.0,
            1.0, 0.0, 1.0,
            -1.0, 0.0, 1.0,
            1.0, 0.0, -1.0,
            -1.0, 0.0, -1.0,
            0.0, 1.0, 1.0,
            0.0, -1.0, 1.0,
            0.0, 1.0, -1.0,
            0.0, -1.0, -1.0,
            1.0, 1.0, 0.0,
            0.0, -1.0, 1.0,
            -1.0, 1.0, 0.0,
            0.0, -1.0, -1.0
    };

    private static final double[] IMPROVED_FLAT_GRAD = {
            1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0,
            1.0, -1.0, 0.0, 0.0, -1.0, -1.0, 0.0, 0.0,
            1.0, 0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0,
            1.0, 0.0, -1.0, 0.0, -1.0, 0.0, -1.0, 0.0,
            0.0, 1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0,
            0.0, 1.0, -1.0, 0.0, 0.0, -1.0, -1.0, 0.0,
            1.0, 1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0,
            -1.0, 1.0, 0.0, 0.0, 0.0, -1.0, -1.0, 0.0
    };

    private static double simplex2dReference(int[] permutation, double x, double y) {
        final double f2 = 0.3660254037844386;
        final double g2 = 0.21132486540518713;
        double skew = (x + y) * f2;
        int i = floor(x + skew);
        int j = floor(y + skew);
        double unskew = (i + j) * g2;
        double x0 = x - (i - unskew);
        double y0 = y - (j - unskew);
        int k;
        int l;
        if (x0 > y0) {
            k = 1;
            l = 0;
        } else {
            k = 0;
            l = 1;
        }

        double x1 = x0 - k + g2;
        double y1 = y0 - l + g2;
        double x2 = x0 - 1.0 + 2.0 * g2;
        double y2 = y0 - 1.0 + 2.0 * g2;
        int i1 = i & 0xFF;
        int j1 = j & 0xFF;
        int k1 = permutation[(i1 + permutation[j1]) & 0xFF] % 12;
        int l1 = permutation[(i1 + k + permutation[(j1 + l) & 0xFF]) & 0xFF] % 12;
        int i2 = permutation[(i1 + 1 + permutation[(j1 + 1) & 0xFF]) & 0xFF] % 12;
        return 70.0 * (
                simplexCornerReference(k1, x0, y0, 0.0, 0.5) +
                simplexCornerReference(l1, x1, y1, 0.0, 0.5) +
                simplexCornerReference(i2, x2, y2, 0.0, 0.5)
        );
    }

    private static double simplex2dOptimized(int[] permutation, double x, double y) {
        final double skew = (x + y) * 0.3660254037844386;
        int cellX = floor(x + skew);
        int cellY = floor(y + skew);
        final double unskew = (cellX + cellY) * 0.21132486540518713;
        final double x0 = x - (cellX - unskew);
        final double y0 = y - (cellY - unskew);

        final int offsetX;
        final int offsetY;
        if (x0 > y0) {
            offsetX = 1;
            offsetY = 0;
        } else {
            offsetX = 0;
            offsetY = 1;
        }

        final double x1 = x0 - offsetX + 0.21132486540518713;
        final double y1 = y0 - offsetY + 0.21132486540518713;
        final double x2 = x0 - 1.0 + 2.0 * 0.21132486540518713;
        final double y2 = y0 - 1.0 + 2.0 * 0.21132486540518713;

        final int ix = cellX & 0xFF;
        final int iy = cellY & 0xFF;
        final int grad0 = permutation[(ix + permutation[iy]) & 0xFF] % 12;
        final int grad1 = permutation[(ix + offsetX + permutation[(iy + offsetY) & 0xFF]) & 0xFF] % 12;
        final int grad2 = permutation[(ix + 1 + permutation[(iy + 1) & 0xFF]) & 0xFF] % 12;

        return 70.0 * (
                simplexCornerOptimized(grad0, x0, y0, 0.0, 0.5) +
                simplexCornerOptimized(grad1, x1, y1, 0.0, 0.5) +
                simplexCornerOptimized(grad2, x2, y2, 0.0, 0.5)
        );
    }

    private static double simplex3dReference(int[] permutation, double x, double y, double z) {
        double skew = (x + y + z) * 0.3333333333333333;
        int i = floor(x + skew);
        int j = floor(y + skew);
        int k = floor(z + skew);
        double unskew = (i + j + k) * 0.16666666666666666;
        double x0 = x - (i - unskew);
        double y0 = y - (j - unskew);
        double z0 = z - (k - unskew);

        int l;
        int i1;
        int j1;
        int k1;
        int l1;
        int i2;
        if (x0 >= y0) {
            if (y0 >= z0) {
                l = 1; i1 = 0; j1 = 0; k1 = 1; l1 = 1; i2 = 0;
            } else if (x0 >= z0) {
                l = 1; i1 = 0; j1 = 0; k1 = 1; l1 = 0; i2 = 1;
            } else {
                l = 0; i1 = 0; j1 = 1; k1 = 1; l1 = 0; i2 = 1;
            }
        } else if (y0 < z0) {
            l = 0; i1 = 0; j1 = 1; k1 = 0; l1 = 1; i2 = 1;
        } else if (x0 < z0) {
            l = 0; i1 = 1; j1 = 0; k1 = 0; l1 = 1; i2 = 1;
        } else {
            l = 0; i1 = 1; j1 = 0; k1 = 1; l1 = 1; i2 = 0;
        }

        double x1 = x0 - l + 0.16666666666666666;
        double y1 = y0 - i1 + 0.16666666666666666;
        double z1 = z0 - j1 + 0.16666666666666666;
        double x2 = x0 - k1 + 0.3333333333333333;
        double y2 = y0 - l1 + 0.3333333333333333;
        double z2 = z0 - i2 + 0.3333333333333333;
        double x3 = x0 - 1.0 + 0.5;
        double y3 = y0 - 1.0 + 0.5;
        double z3 = z0 - 1.0 + 0.5;

        int ix = i & 0xFF;
        int iy = j & 0xFF;
        int iz = k & 0xFF;
        int grad0 = permutation[(ix + permutation[(iy + permutation[iz & 0xFF]) & 0xFF]) & 0xFF] % 12;
        int grad1 = permutation[(ix + l + permutation[(iy + i1 + permutation[(iz + j1) & 0xFF]) & 0xFF]) & 0xFF] % 12;
        int grad2 = permutation[(ix + k1 + permutation[(iy + l1 + permutation[(iz + i2) & 0xFF]) & 0xFF]) & 0xFF] % 12;
        int grad3 = permutation[(ix + 1 + permutation[(iy + 1 + permutation[(iz + 1) & 0xFF]) & 0xFF]) & 0xFF] % 12;

        return 32.0 * (
                simplexCornerReference(grad0, x0, y0, z0, 0.6) +
                simplexCornerReference(grad1, x1, y1, z1, 0.6) +
                simplexCornerReference(grad2, x2, y2, z2, 0.6) +
                simplexCornerReference(grad3, x3, y3, z3, 0.6)
        );
    }

    private static double simplex3dOptimized(int[] permutation, double x, double y, double z) {
        final double skew = (x + y + z) * 0.3333333333333333;
        int cellX = floor(x + skew);
        int cellY = floor(y + skew);
        int cellZ = floor(z + skew);
        final double unskew = (cellX + cellY + cellZ) * 0.16666666666666666;
        final double x0 = x - (cellX - unskew);
        final double y0 = y - (cellY - unskew);
        final double z0 = z - (cellZ - unskew);

        final int i1;
        final int j1;
        final int k1;
        final int i2;
        final int j2;
        final int k2;
        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1;
            }
        } else if (y0 < z0) {
            i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1;
        } else if (x0 < z0) {
            i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1;
        } else {
            i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
        }

        final double x1 = x0 - i1 + 0.16666666666666666;
        final double y1 = y0 - j1 + 0.16666666666666666;
        final double z1 = z0 - k1 + 0.16666666666666666;
        final double x2 = x0 - i2 + 0.3333333333333333;
        final double y2 = y0 - j2 + 0.3333333333333333;
        final double z2 = z0 - k2 + 0.3333333333333333;
        final double x3 = x0 - 1.0 + 0.5;
        final double y3 = y0 - 1.0 + 0.5;
        final double z3 = z0 - 1.0 + 0.5;

        final int ix = cellX & 0xFF;
        final int iy = cellY & 0xFF;
        final int iz = cellZ & 0xFF;
        final int grad0 = permutation[(ix + permutation[(iy + permutation[iz & 0xFF]) & 0xFF]) & 0xFF] % 12;
        final int grad1 = permutation[(ix + i1 + permutation[(iy + j1 + permutation[(iz + k1) & 0xFF]) & 0xFF]) & 0xFF] % 12;
        final int grad2 = permutation[(ix + i2 + permutation[(iy + j2 + permutation[(iz + k2) & 0xFF]) & 0xFF]) & 0xFF] % 12;
        final int grad3 = permutation[(ix + 1 + permutation[(iy + 1 + permutation[(iz + 1) & 0xFF]) & 0xFF]) & 0xFF] % 12;

        return 32.0 * (
                simplexCornerOptimized(grad0, x0, y0, z0, 0.6) +
                simplexCornerOptimized(grad1, x1, y1, z1, 0.6) +
                simplexCornerOptimized(grad2, x2, y2, z2, 0.6) +
                simplexCornerOptimized(grad3, x3, y3, z3, 0.6)
        );
    }

    private static double simplexCornerReference(int gradientIndex, double x, double y, double z, double offset) {
        double atten = offset - x * x - y * y - z * z;
        if (atten < 0.0) {
            return 0.0;
        }
        atten *= atten;
        int[] gradient = SIMPLEX_GRADIENT[gradientIndex];
        return atten * atten * (gradient[0] * x + gradient[1] * y + gradient[2] * z);
    }

    private static double simplexCornerOptimized(int gradientIndex, double x, double y, double z, double offset) {
        double atten = offset - x * x - y * y - z * z;
        if (atten < 0.0) {
            return 0.0;
        }
        atten *= atten;
        int gradIndex3 = gradientIndex * 3;
        return atten * atten * (
                FLAT_SIMPLEX_GRAD[gradIndex3] * x +
                FLAT_SIMPLEX_GRAD[gradIndex3 + 1] * y +
                FLAT_SIMPLEX_GRAD[gradIndex3 + 2] * z
        );
    }

    private static double improvedNoiseReference(byte[] permutation, double xo, double yo, double zo, double x, double y, double z, double yScale, double yMax) {
        double inputX = x + xo;
        double inputY = y + yo;
        double inputZ = z + zo;
        int gridX = floor(inputX);
        int gridY = floor(inputY);
        int gridZ = floor(inputZ);
        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;

        double yOffset;
        if (yScale != 0.0) {
            double range = (yMax >= 0.0 && yMax < deltaY) ? yMax : deltaY;
            yOffset = floor(range / yScale + 1.0E-7) * yScale;
        } else {
            yOffset = 0.0;
        }

        return improvedSampleAndLerpReference(permutation, gridX, gridY, gridZ, deltaX, deltaY - yOffset, deltaZ, deltaY);
    }

    private static double improvedNoiseOptimized(byte[] permutation, double xo, double yo, double zo, double x, double y, double z, double yScale, double yMax) {
        double inputX = x + xo;
        double inputY = y + yo;
        double inputZ = z + zo;

        int gridX = floor(inputX);
        int gridY = floor(inputY);
        int gridZ = floor(inputZ);

        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;

        double weirdDeltaY;
        if (yScale != 0.0) {
            double range = (yMax >= 0.0 && yMax < deltaY) ? yMax : deltaY;
            double scaled = range / yScale + 1.0E-7;
            int scaledFloor = (int) scaled;
            if (scaled < scaledFloor) scaledFloor--;
            weirdDeltaY = deltaY - scaledFloor * yScale;
        } else {
            weirdDeltaY = deltaY;
        }

        return improvedSampleAndLerpOptimized(permutation, gridX, gridY, gridZ, deltaX, weirdDeltaY, deltaZ, deltaY);
    }

    private static double improvedNoiseWithDerivativeReference(byte[] permutation, double xo, double yo, double zo, double x, double y, double z, double[] derivatives) {
        double inputX = x + xo;
        double inputY = y + yo;
        double inputZ = z + zo;
        int gridX = floor(inputX);
        int gridY = floor(inputY);
        int gridZ = floor(inputZ);
        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;
        return improvedSampleWithDerivativeReference(permutation, gridX, gridY, gridZ, deltaX, deltaY, deltaZ, derivatives);
    }

    private static double improvedNoiseWithDerivativeOptimized(byte[] permutation, double xo, double yo, double zo, double x, double y, double z, double[] derivatives) {
        double inputX = x + xo;
        double inputY = y + yo;
        double inputZ = z + zo;
        int gridX = floor(inputX);
        int gridY = floor(inputY);
        int gridZ = floor(inputZ);
        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;
        return improvedSampleWithDerivativeOptimized(permutation, gridX, gridY, gridZ, deltaX, deltaY, deltaZ, derivatives);
    }

    private static double improvedSampleAndLerpReference(byte[] permutation, int gridX, int gridY, int gridZ, double deltaX, double weirdDeltaY, double deltaZ, double deltaY) {
        int i = p(permutation, gridX);
        int j = p(permutation, gridX + 1);
        int k = p(permutation, i + gridY);
        int l = p(permutation, i + gridY + 1);
        int i1 = p(permutation, j + gridY);
        int j1 = p(permutation, j + gridY + 1);
        double d0 = improvedGradDot(p(permutation, k + gridZ), deltaX, weirdDeltaY, deltaZ);
        double d1 = improvedGradDot(p(permutation, i1 + gridZ), deltaX - 1.0, weirdDeltaY, deltaZ);
        double d2 = improvedGradDot(p(permutation, l + gridZ), deltaX, weirdDeltaY - 1.0, deltaZ);
        double d3 = improvedGradDot(p(permutation, j1 + gridZ), deltaX - 1.0, weirdDeltaY - 1.0, deltaZ);
        double d4 = improvedGradDot(p(permutation, k + gridZ + 1), deltaX, weirdDeltaY, deltaZ - 1.0);
        double d5 = improvedGradDot(p(permutation, i1 + gridZ + 1), deltaX - 1.0, weirdDeltaY, deltaZ - 1.0);
        double d6 = improvedGradDot(p(permutation, l + gridZ + 1), deltaX, weirdDeltaY - 1.0, deltaZ - 1.0);
        double d7 = improvedGradDot(p(permutation, j1 + gridZ + 1), deltaX - 1.0, weirdDeltaY - 1.0, deltaZ - 1.0);
        double u = smoothstep(deltaX);
        double v = smoothstep(deltaY);
        double w = smoothstep(deltaZ);
        return lerp3(u, v, w, d0, d1, d2, d3, d4, d5, d6, d7);
    }

    private static double improvedSampleAndLerpOptimized(byte[] permutation, int gridX, int gridY, int gridZ, double x, double wy, double z, double y) {
        int X = gridX & 0xFF;
        int Y = gridY & 0xFF;
        int Z = gridZ & 0xFF;

        int A = (permutation[X] & 0xFF) + Y;
        int AA = (permutation[A & 0xFF] & 0xFF) + Z;
        int AB = (permutation[(A + 1) & 0xFF] & 0xFF) + Z;

        int B = (permutation[(X + 1) & 0xFF] & 0xFF) + Y;
        int BA = (permutation[B & 0xFF] & 0xFF) + Z;
        int BB = (permutation[(B + 1) & 0xFF] & 0xFF) + Z;

        int gi000 = (permutation[AA & 0xFF] & 15) << 2;
        int gi001 = (permutation[(AA + 1) & 0xFF] & 15) << 2;
        int gi010 = (permutation[AB & 0xFF] & 15) << 2;
        int gi011 = (permutation[(AB + 1) & 0xFF] & 15) << 2;
        int gi100 = (permutation[BA & 0xFF] & 15) << 2;
        int gi101 = (permutation[(BA + 1) & 0xFF] & 15) << 2;
        int gi110 = (permutation[BB & 0xFF] & 15) << 2;
        int gi111 = (permutation[(BB + 1) & 0xFF] & 15) << 2;

        double x1 = x - 1.0;
        double wy1 = wy - 1.0;
        double z1 = z - 1.0;

        double n000 = improvedFlatDot(gi000, x, wy, z);
        double n100 = improvedFlatDot(gi100, x1, wy, z);
        double n001 = improvedFlatDot(gi001, x, wy, z1);
        double n101 = improvedFlatDot(gi101, x1, wy, z1);
        double n011 = improvedFlatDot(gi011, x, wy1, z1);
        double n111 = improvedFlatDot(gi111, x1, wy1, z1);
        double n010 = improvedFlatDot(gi010, x, wy1, z);
        double n110 = improvedFlatDot(gi110, x1, wy1, z);

        double u = smoothstep(x);
        double v = smoothstep(y);
        double w = smoothstep(z);
        double lerpX1 = n000 + u * (n100 - n000);
        double lerpX2 = n010 + u * (n110 - n010);
        double lerpX3 = n001 + u * (n101 - n001);
        double lerpX4 = n011 + u * (n111 - n011);
        double lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
        double lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);
        return lerpY1 + w * (lerpY2 - lerpY1);
    }

    private static double improvedSampleWithDerivativeReference(byte[] permutation, int gridX, int gridY, int gridZ, double deltaX, double deltaY, double deltaZ, double[] derivatives) {
        int i = p(permutation, gridX);
        int j = p(permutation, gridX + 1);
        int k = p(permutation, i + gridY);
        int l = p(permutation, i + gridY + 1);
        int i1 = p(permutation, j + gridY);
        int j1 = p(permutation, j + gridY + 1);
        int k1 = p(permutation, k + gridZ);
        int l1 = p(permutation, i1 + gridZ);
        int i2 = p(permutation, l + gridZ);
        int j2 = p(permutation, j1 + gridZ);
        int k2 = p(permutation, k + gridZ + 1);
        int l2 = p(permutation, i1 + gridZ + 1);
        int i3 = p(permutation, l + gridZ + 1);
        int j3 = p(permutation, j1 + gridZ + 1);

        int[] g000 = SIMPLEX_GRADIENT[k1 & 15];
        int[] g100 = SIMPLEX_GRADIENT[l1 & 15];
        int[] g010 = SIMPLEX_GRADIENT[i2 & 15];
        int[] g110 = SIMPLEX_GRADIENT[j2 & 15];
        int[] g001 = SIMPLEX_GRADIENT[k2 & 15];
        int[] g101 = SIMPLEX_GRADIENT[l2 & 15];
        int[] g011 = SIMPLEX_GRADIENT[i3 & 15];
        int[] g111 = SIMPLEX_GRADIENT[j3 & 15];

        double d0 = dot(g000, deltaX, deltaY, deltaZ);
        double d1 = dot(g100, deltaX - 1.0, deltaY, deltaZ);
        double d2 = dot(g010, deltaX, deltaY - 1.0, deltaZ);
        double d3 = dot(g110, deltaX - 1.0, deltaY - 1.0, deltaZ);
        double d4 = dot(g001, deltaX, deltaY, deltaZ - 1.0);
        double d5 = dot(g101, deltaX - 1.0, deltaY, deltaZ - 1.0);
        double d6 = dot(g011, deltaX, deltaY - 1.0, deltaZ - 1.0);
        double d7 = dot(g111, deltaX - 1.0, deltaY - 1.0, deltaZ - 1.0);
        double u = smoothstep(deltaX);
        double v = smoothstep(deltaY);
        double w = smoothstep(deltaZ);

        double d11 = lerp3(u, v, w, g000[0], g100[0], g010[0], g110[0], g001[0], g101[0], g011[0], g111[0]);
        double d12 = lerp3(u, v, w, g000[1], g100[1], g010[1], g110[1], g001[1], g101[1], g011[1], g111[1]);
        double d13 = lerp3(u, v, w, g000[2], g100[2], g010[2], g110[2], g001[2], g101[2], g011[2], g111[2]);
        double d14 = lerp2(v, w, d1 - d0, d3 - d2, d5 - d4, d7 - d6);
        double d15 = lerp2(w, u, d2 - d0, d6 - d4, d3 - d1, d7 - d5);
        double d16 = lerp2(u, v, d4 - d0, d5 - d1, d6 - d2, d7 - d3);
        double d17 = smoothstepDerivative(deltaX);
        double d18 = smoothstepDerivative(deltaY);
        double d19 = smoothstepDerivative(deltaZ);
        derivatives[0] += d11 + d17 * d14;
        derivatives[1] += d12 + d18 * d15;
        derivatives[2] += d13 + d19 * d16;
        return lerp3(u, v, w, d0, d1, d2, d3, d4, d5, d6, d7);
    }

    private static double improvedSampleWithDerivativeOptimized(byte[] permutation, int gridX, int gridY, int gridZ, double x, double y, double z, double[] derivatives) {
        int X = gridX & 0xFF;
        int Y = gridY & 0xFF;
        int Z = gridZ & 0xFF;

        int px0 = permutation[X] & 0xFF;
        int px1 = permutation[(X + 1) & 0xFF] & 0xFF;
        int a = (px0 + Y) & 0xFF;
        int b = (px1 + Y) & 0xFF;
        int aa = (permutation[a] & 0xFF) + Z;
        int ab = (permutation[(a + 1) & 0xFF] & 0xFF) + Z;
        int ba = (permutation[b] & 0xFF) + Z;
        int bb = (permutation[(b + 1) & 0xFF] & 0xFF) + Z;

        int gi000 = (permutation[aa & 0xFF] & 15) << 2;
        int gi001 = (permutation[(aa + 1) & 0xFF] & 15) << 2;
        int gi010 = (permutation[ab & 0xFF] & 15) << 2;
        int gi011 = (permutation[(ab + 1) & 0xFF] & 15) << 2;
        int gi100 = (permutation[ba & 0xFF] & 15) << 2;
        int gi101 = (permutation[(ba + 1) & 0xFF] & 15) << 2;
        int gi110 = (permutation[bb & 0xFF] & 15) << 2;
        int gi111 = (permutation[(bb + 1) & 0xFF] & 15) << 2;

        double x1 = x - 1.0;
        double y1 = y - 1.0;
        double z1 = z - 1.0;

        double g000x = IMPROVED_FLAT_GRAD[gi000];
        double g000y = IMPROVED_FLAT_GRAD[gi000 | 1];
        double g000z = IMPROVED_FLAT_GRAD[gi000 | 2];
        double g001x = IMPROVED_FLAT_GRAD[gi001];
        double g001y = IMPROVED_FLAT_GRAD[gi001 | 1];
        double g001z = IMPROVED_FLAT_GRAD[gi001 | 2];
        double g010x = IMPROVED_FLAT_GRAD[gi010];
        double g010y = IMPROVED_FLAT_GRAD[gi010 | 1];
        double g010z = IMPROVED_FLAT_GRAD[gi010 | 2];
        double g011x = IMPROVED_FLAT_GRAD[gi011];
        double g011y = IMPROVED_FLAT_GRAD[gi011 | 1];
        double g011z = IMPROVED_FLAT_GRAD[gi011 | 2];
        double g100x = IMPROVED_FLAT_GRAD[gi100];
        double g100y = IMPROVED_FLAT_GRAD[gi100 | 1];
        double g100z = IMPROVED_FLAT_GRAD[gi100 | 2];
        double g101x = IMPROVED_FLAT_GRAD[gi101];
        double g101y = IMPROVED_FLAT_GRAD[gi101 | 1];
        double g101z = IMPROVED_FLAT_GRAD[gi101 | 2];
        double g110x = IMPROVED_FLAT_GRAD[gi110];
        double g110y = IMPROVED_FLAT_GRAD[gi110 | 1];
        double g110z = IMPROVED_FLAT_GRAD[gi110 | 2];
        double g111x = IMPROVED_FLAT_GRAD[gi111];
        double g111y = IMPROVED_FLAT_GRAD[gi111 | 1];
        double g111z = IMPROVED_FLAT_GRAD[gi111 | 2];

        double n000 = g000x * x + g000y * y + g000z * z;
        double n100 = g100x * x1 + g100y * y + g100z * z;
        double n010 = g010x * x + g010y * y1 + g010z * z;
        double n110 = g110x * x1 + g110y * y1 + g110z * z;
        double n001 = g001x * x + g001y * y + g001z * z1;
        double n101 = g101x * x1 + g101y * y + g101z * z1;
        double n011 = g011x * x + g011y * y1 + g011z * z1;
        double n111 = g111x * x1 + g111y * y1 + g111z * z1;

        double uFactor = x * 6.0 - 15.0;
        double vFactor = y * 6.0 - 15.0;
        double wFactor = z * 6.0 - 15.0;
        double u = x * x * x * (x * uFactor + 10.0);
        double v = y * y * y * (y * vFactor + 10.0);
        double w = z * z * z * (z * wFactor + 10.0);

        double nx00 = n000 + u * (n100 - n000);
        double nx10 = n010 + u * (n110 - n010);
        double nx01 = n001 + u * (n101 - n001);
        double nx11 = n011 + u * (n111 - n011);
        double nxy0 = nx00 + v * (nx10 - nx00);
        double nxy1 = nx01 + v * (nx11 - nx01);

        double gradX0 = (g000x + u * (g100x - g000x)) + v * ((g010x + u * (g110x - g010x)) - (g000x + u * (g100x - g000x)));
        double gradX1 = (g001x + u * (g101x - g001x)) + v * ((g011x + u * (g111x - g011x)) - (g001x + u * (g101x - g001x)));
        double gradY0 = (g000y + u * (g100y - g000y)) + v * ((g010y + u * (g110y - g010y)) - (g000y + u * (g100y - g000y)));
        double gradY1 = (g001y + u * (g101y - g001y)) + v * ((g011y + u * (g111y - g011y)) - (g001y + u * (g101y - g001y)));
        double gradZ0 = (g000z + u * (g100z - g000z)) + v * ((g010z + u * (g110z - g010z)) - (g000z + u * (g100z - g000z)));
        double gradZ1 = (g001z + u * (g101z - g001z)) + v * ((g011z + u * (g111z - g011z)) - (g001z + u * (g101z - g001z)));

        double d14 = ((n100 - n000) + v * ((n110 - n010) - (n100 - n000))) + w * (((n101 - n001) + v * ((n111 - n011) - (n101 - n001))) - ((n100 - n000) + v * ((n110 - n010) - (n100 - n000))));
        double d15 = ((n010 - n000) + w * ((n011 - n001) - (n010 - n000))) + u * (((n110 - n100) + w * ((n111 - n101) - (n110 - n100))) - ((n010 - n000) + w * ((n011 - n001) - (n010 - n000))));
        double d16 = ((n001 - n000) + u * ((n101 - n100) - (n001 - n000))) + v * (((n011 - n010) + u * ((n111 - n110) - (n011 - n010))) - ((n001 - n000) + u * ((n101 - n100) - (n001 - n000))));

        double du = smoothstepDerivative(x);
        double dv = smoothstepDerivative(y);
        double dw = smoothstepDerivative(z);

        derivatives[0] += (gradX0 + w * (gradX1 - gradX0)) + du * d14;
        derivatives[1] += (gradY0 + w * (gradY1 - gradY0)) + dv * d15;
        derivatives[2] += (gradZ0 + w * (gradZ1 - gradZ0)) + dw * d16;

        return nxy0 + w * (nxy1 - nxy0);
    }

    private static int p(byte[] permutation, int index) {
        return permutation[index & 0xFF] & 0xFF;
    }

    private static double dot(int[] gradient, double x, double y, double z) {
        return gradient[0] * x + gradient[1] * y + gradient[2] * z;
    }

    private static double improvedGradDot(int gradIndex, double x, double y, double z) {
        int[] gradient = SIMPLEX_GRADIENT[gradIndex & 15];
        return dot(gradient, x, y, z);
    }

    private static double improvedFlatDot(int gradIndex4, double x, double y, double z) {
        return IMPROVED_FLAT_GRAD[gradIndex4] * x + IMPROVED_FLAT_GRAD[gradIndex4 | 1] * y + IMPROVED_FLAT_GRAD[gradIndex4 | 2] * z;
    }

    private static double smoothstep(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double smoothstepDerivative(double value) {
        return 30.0 * value * value * (value * (value - 2.0) + 1.0);
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static double lerp2(double deltaX, double deltaY, double x0y0, double x1y0, double x0y1, double x1y1) {
        return lerp(deltaY, lerp(deltaX, x0y0, x1y0), lerp(deltaX, x0y1, x1y1));
    }

    private static double lerp3(double deltaX, double deltaY, double deltaZ, double x0y0z0, double x1y0z0, double x0y1z0, double x1y1z0, double x0y0z1, double x1y0z1, double x0y1z1, double x1y1z1) {
        return lerp(deltaZ, lerp2(deltaX, deltaY, x0y0z0, x1y0z0, x0y1z0, x1y1z0), lerp2(deltaX, deltaY, x0y0z1, x1y0z1, x0y1z1, x1y1z1));
    }

    private static int floor(double value) {
        int floor = (int) value;
        return value < floor ? floor - 1 : floor;
    }
}
