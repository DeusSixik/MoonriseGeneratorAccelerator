package dev.sixik.generator_accelerator.common.aquifer.benchmark;

import dev.sixik.generator_accelerator.common.aquifer.GAAquiferColumnBandNearest;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferGrid;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferNearest;

import java.util.Arrays;
import java.util.Locale;

public final class GAAquiferGridQuickBenchmark {
    private static volatile long sink;

    private GAAquiferGridQuickBenchmark() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        Fixture fixture = Fixture.create();
        GAAquiferNearest nearest = new GAAquiferNearest();
        GAAquiferColumnBandNearest band = new GAAquiferColumnBandNearest();

        System.out.printf(Locale.ROOT,
                "aquifer.grid warmup=%d iterations=%d samples=%d columns=%d height=%d%n",
                options.warmup,
                options.iterations,
                options.samples,
                options.columns,
                options.height);

        for (int i = 0; i < options.warmup; i++) {
            runScalar(fixture, nearest, options);
            band.invalidate();
            runColumnBand(fixture, band, nearest, options);
        }

        long[] scalarNanos = measure("scalar", () -> runScalar(fixture, nearest, options), options);
        long[] bandNanos = measure("column_band", () -> {
            band.invalidate();
            return runColumnBand(fixture, band, nearest, options);
        }, options);

        printResult("scalar", scalarNanos, options);
        printResult("column_band", bandNanos, options);
        Arrays.sort(scalarNanos);
        Arrays.sort(bandNanos);
        long measuredOps = operations(options) * options.iterations;
        double scalarMedian = scalarNanos[scalarNanos.length / 2] / (double) measuredOps;
        double bandMedian = bandNanos[bandNanos.length / 2] / (double) measuredOps;
        System.out.printf(Locale.ROOT,
                "result speedup=%.3fx scalar_ns_per_lookup=%.2f column_band_ns_per_lookup=%.2f sink=%d%n",
                scalarMedian / bandMedian,
                scalarMedian,
                bandMedian,
                sink);
    }

    private static long[] measure(String name, Runner runner, Options options) {
        long[] sampleNanos = new long[options.samples];
        for (int sample = 0; sample < options.samples; sample++) {
            long acc = 0L;
            long start = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                acc += runner.run();
            }
            long elapsed = System.nanoTime() - start;
            sink = acc;
            sampleNanos[sample] = elapsed;
            System.out.printf(Locale.ROOT,
                    "sample=%d name=%s total_ms=%.3f ns_per_lookup=%.2f%n",
                    sample + 1,
                    name,
                    elapsed / 1_000_000.0,
                    elapsed / (double) (operations(options) * options.iterations));
        }
        return sampleNanos;
    }

    private static long runScalar(Fixture fixture, GAAquiferNearest nearest, Options options) {
        long acc = 0L;
        int height = options.height;
        int columns = options.columns;
        for (int column = 0; column < columns; column++) {
            int x = fixture.columnX[column & 255];
            int z = fixture.columnZ[column & 255];
            for (int y = fixture.minY; y < fixture.minY + height; y++) {
                fixture.grid.nearest(x, y, z, nearest);
                acc += nearest.idx1 + nearest.dist1;
            }
        }
        return acc;
    }

    private static long runColumnBand(
            Fixture fixture,
            GAAquiferColumnBandNearest band,
            GAAquiferNearest nearest,
            Options options
    ) {
        long acc = 0L;
        int height = options.height;
        int columns = options.columns;
        for (int column = 0; column < columns; column++) {
            int x = fixture.columnX[column & 255];
            int z = fixture.columnZ[column & 255];
            for (int y = fixture.minY; y < fixture.minY + height; y++) {
                fixture.grid.nearestColumnBand(x, y, z, band, nearest);
                acc += nearest.idx1 + nearest.dist1;
            }
        }
        return acc;
    }

    private static void printResult(String name, long[] nanos, Options options) {
        Arrays.sort(nanos);
        long ops = operations(options) * options.iterations;
        System.out.printf(Locale.ROOT,
                "%s best_ns_per_lookup=%.2f median_ns_per_lookup=%.2f worst_ns_per_lookup=%.2f%n",
                name,
                nanos[0] / (double) ops,
                nanos[nanos.length / 2] / (double) ops,
                nanos[nanos.length - 1] / (double) ops);
    }

    private static long operations(Options options) {
        return (long) options.columns * options.height;
    }

    @FunctionalInterface
    private interface Runner {
        long run();
    }

    private record Options(int warmup, int iterations, int samples, int columns, int height) {
        private static Options parse(String[] args) {
            int warmup = 100;
            int iterations = 200;
            int samples = 5;
            int columns = 256;
            int height = 384;
            for (String arg : args) {
                if (arg.startsWith("--warmup=")) {
                    warmup = parsePositive(arg, "--warmup=");
                } else if (arg.startsWith("--iterations=")) {
                    iterations = parsePositive(arg, "--iterations=");
                } else if (arg.startsWith("--samples=")) {
                    samples = parsePositive(arg, "--samples=");
                } else if (arg.startsWith("--columns=")) {
                    columns = parsePositive(arg, "--columns=");
                } else if (arg.startsWith("--height=")) {
                    height = parsePositive(arg, "--height=");
                }
            }
            return new Options(warmup, iterations, samples, columns, height);
        }

        private static int parsePositive(String arg, String prefix) {
            int value = Integer.parseInt(arg.substring(prefix.length()));
            if (value <= 0) {
                throw new IllegalArgumentException(prefix + " must be positive");
            }
            return value;
        }
    }

    private record Fixture(GAAquiferGrid grid, int[] columnX, int[] columnZ, int minY) {
        private static Fixture create() {
            int gridSizeX = 7;
            int gridSizeY = 48;
            int gridSizeZ = 7;
            int minGridX = -3;
            int minGridY = -12;
            int minGridZ = -3;
            int size = gridSizeX * gridSizeY * gridSizeZ;
            int[] xs = new int[size];
            int[] ys = new int[size];
            int[] zs = new int[size];
            for (int y = 0; y < gridSizeY; y++) {
                for (int z = 0; z < gridSizeZ; z++) {
                    for (int x = 0; x < gridSizeX; x++) {
                        int index = ((y * gridSizeZ) + z) * gridSizeX + x;
                        int gx = x + minGridX;
                        int gy = y + minGridY;
                        int gz = z + minGridZ;
                        xs[index] = gx * 16 + positiveMix(gx, gy, gz, 10);
                        ys[index] = gy * 12 + positiveMix(gy, gz, gx, 9);
                        zs[index] = gz * 16 + positiveMix(gz, gx, gy, 10);
                    }
                }
            }

            int[] columnX = new int[256];
            int[] columnZ = new int[256];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = (z << 4) | x;
                    columnX[index] = x;
                    columnZ[index] = z;
                }
            }
            return new Fixture(new GAAquiferGrid(gridSizeX, gridSizeZ, minGridX, minGridY, minGridZ, xs, ys, zs),
                    columnX,
                    columnZ,
                    -64);
        }

        private static int positiveMix(int a, int b, int c, int bound) {
            int h = a * 73428767 ^ b * 91227153 ^ c * 42317861;
            h ^= h >>> 16;
            return (h & Integer.MAX_VALUE) % bound;
        }
    }
}
