package dev.sixik.generator_accelerator.common.noise;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Pure-Java scalar noise kernels for repeated worldgen sampling.
 *
 * <p>The math mirrors Minecraft 1.21.1's Mojang-mapped {@link ImprovedNoise}
 * implementation: vanilla octave order, {@code PerlinNoise.wrap} domain wrapping,
 * and double-precision accumulation. Hot loops allocate nothing; per-octave wrapper
 * objects are created once when a {@code PerlinNoise} instance is constructed.</p>
 */
public final class FastVectorNoise {
    private static final double WRAP_DOMAIN = 33554432.0;
    private static final double SHIFT_UP_EPSILON = 1.0E-7F;

    private static final double[] SIMPLEX_GRADIENTS = {
            1.0, 1.0, 0.0, 0.0,
            -1.0, 1.0, 0.0, 0.0,
            1.0, -1.0, 0.0, 0.0,
            -1.0, -1.0, 0.0, 0.0,
            1.0, 0.0, 1.0, 0.0,
            -1.0, 0.0, 1.0, 0.0,
            1.0, 0.0, -1.0, 0.0,
            -1.0, 0.0, -1.0, 0.0,
            0.0, 1.0, 1.0, 0.0,
            0.0, -1.0, 1.0, 0.0,
            0.0, 1.0, -1.0, 0.0,
            0.0, -1.0, -1.0, 0.0,
            1.0, 1.0, 0.0, 0.0,
            0.0, -1.0, 1.0, 0.0,
            -1.0, 1.0, 0.0, 0.0,
            0.0, -1.0, -1.0, 0.0,
    };

    private final byte[] p;
    private final double xo;
    private final double yo;
    private final double zo;

    public FastVectorNoise(byte[] p, double xo, double yo, double zo) {
        if (p == null || p.length < 256) {
            throw new IllegalArgumentException("ImprovedNoise permutation table must contain at least 256 entries");
        }
        this.p = p;
        this.xo = xo;
        this.yo = yo;
        this.zo = zo;
    }

    /** Mirrors {@code PerlinNoise.wrap}. */
    public static double wrap(double value) {
        double scaled = value / WRAP_DOMAIN + 0.5;
        long truncated = (long) scaled;
        long floored = scaled < (double) truncated ? truncated - 1L : truncated;
        return value - (double) floored * WRAP_DOMAIN;
    }

    /** Mirrors {@code ImprovedNoise.noise(x, y, z)} without virtual dispatch. */
    public static double sample(ImprovedNoise noise, double x, double y, double z) {
        return sample(noise.p, noise.xo, noise.yo, noise.zo, x, y, z, 0.0, 0.0);
    }

    /** Mirrors {@code ImprovedNoise.noise(x, y, z, yScale, yMax)} without virtual dispatch. */
    public static double sample(ImprovedNoise noise, double x, double y, double z, double yScale, double yMax) {
        return sample(noise.p, noise.xo, noise.yo, noise.zo, x, y, z, yScale, yMax);
    }

    public static double sample(byte[] p, double xo, double yo, double zo, double x, double y, double z) {
        return sample(p, xo, yo, zo, x, y, z, 0.0, 0.0);
    }

    public static double sample(byte[] p, double xo, double yo, double zo,
                                double x, double y, double z, double yScale, double yMax) {
        double inputX = x + xo;
        double inputY = y + yo;
        double inputZ = z + zo;
        int gridX = floor(inputX);
        int gridY = floor(inputY);
        int gridZ = floor(inputZ);
        double deltaX = inputX - (double) gridX;
        double deltaY = inputY - (double) gridY;
        double deltaZ = inputZ - (double) gridZ;
        double yOffset;
        if (yScale != 0.0) {
            double range = yMax >= 0.0 && yMax < deltaY ? yMax : deltaY;
            yOffset = (double) floor(range / yScale + SHIFT_UP_EPSILON) * yScale;
        } else {
            yOffset = 0.0;
        }
        return sampleAndLerp(p, gridX, gridY, gridZ, deltaX, deltaY - yOffset, deltaZ, deltaY);
    }

    /** Scalar single-point path used by Perlin octave loops. Coordinates must already be wrapped. */
    public double computeSingle(double x, double y, double z) {
        return sample(this.p, this.xo, this.yo, this.zo, x, y, z);
    }

    /**
     * Adds a wrapped vertical column for one octave into {@code buffer}.
     * Coordinates are block coordinates plus caller-provided scale factors.
     */
    public void fillWrappedColumn(double[] buffer, int x, int z, int yStart, int count,
                                  double scaleX, double scaleY, double scaleZ, double amplitude) {
        double wrappedX = wrap((double) x * scaleX);
        double wrappedZ = wrap((double) z * scaleZ);
        for (int i = 0; i < count; i++) {
            double wrappedY = wrap((double) (yStart + i) * scaleY);
            buffer[i] += sample(this.p, this.xo, this.yo, this.zo, wrappedX, wrappedY, wrappedZ) * amplitude;
        }
    }

    /** Backwards-compatible entry point; now scalar/double rather than vectorized/float. */
    public void fillColumnVectorized(double[] buffer, int x, int z, int yStart, int count,
                                     double scaleX, double scaleY, double scaleZ,
                                     double amplitude, double valueFactor) {
        fillWrappedColumn(buffer, x, z, yStart, count, scaleX, scaleY, scaleZ, amplitude * valueFactor);
    }

    private static double sampleAndLerp(byte[] p, int gridX, int gridY, int gridZ,
                                        double deltaX, double weirdDeltaY, double deltaZ, double deltaY) {
        int l = p(p, gridX);
        int m = p(p, gridX + 1);
        int n = p(p, l + gridY);
        int o = p(p, l + gridY + 1);
        int q = p(p, m + gridY);
        int r = p(p, m + gridY + 1);

        double n000 = gradDot(p(p, n + gridZ), deltaX, weirdDeltaY, deltaZ);
        double n100 = gradDot(p(p, q + gridZ), deltaX - 1.0, weirdDeltaY, deltaZ);
        double n010 = gradDot(p(p, o + gridZ), deltaX, weirdDeltaY - 1.0, deltaZ);
        double n110 = gradDot(p(p, r + gridZ), deltaX - 1.0, weirdDeltaY - 1.0, deltaZ);
        double n001 = gradDot(p(p, n + gridZ + 1), deltaX, weirdDeltaY, deltaZ - 1.0);
        double n101 = gradDot(p(p, q + gridZ + 1), deltaX - 1.0, weirdDeltaY, deltaZ - 1.0);
        double n011 = gradDot(p(p, o + gridZ + 1), deltaX, weirdDeltaY - 1.0, deltaZ - 1.0);
        double n111 = gradDot(p(p, r + gridZ + 1), deltaX - 1.0, weirdDeltaY - 1.0, deltaZ - 1.0);

        double sx = smoothstep(deltaX);
        double sy = smoothstep(deltaY);
        double sz = smoothstep(deltaZ);
        double x00 = lerp(sx, n000, n100);
        double x10 = lerp(sx, n010, n110);
        double x01 = lerp(sx, n001, n101);
        double x11 = lerp(sx, n011, n111);
        return lerp(sz, lerp(sy, x00, x10), lerp(sy, x01, x11));
    }

    private static int p(byte[] p, int index) {
        return p[index & 0xFF] & 0xFF;
    }

    private static double gradDot(int hash, double x, double y, double z) {
        int index = (hash & 0xF) << 2;
        return SIMPLEX_GRADIENTS[index] * x
                + SIMPLEX_GRADIENTS[index | 1] * y
                + SIMPLEX_GRADIENTS[index | 2] * z;
    }

    private static double smoothstep(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }
}
