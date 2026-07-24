import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.GpuBackendTarget;
import net.sixik.ga_utils.javatogpu.api.GpuPreparedLauncher;
import net.sixik.ga_utils.javatogpu.api.GpuScope;
import net.sixik.ga_utils.javatogpu.api.JavaToGpu;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUGlobal;
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeCompileOptions;

import java.security.CodeSource;
import java.util.Arrays;
import java.util.Locale;

/**
 * Standalone JavaToGpu prepared-launcher benchmark.
 *
 * <p>Copy this file into any test project that has JavaToGpu on the classpath.
 * It measures cold prepare cost separately from cached GpuPreparedLauncher.invoke(...)
 * cost, then validates GPU output against a CPU mirror.</p>
 *
 * <p>Example:</p>
 * <pre>
 * javac -cp libs/processor-0.1.0-alpha.4.jar extended_docs/javaToGpu/JavaToGpuPreparedLauncherBenchmark.java
 * java  -cp "processor + JavaToGpu runtime deps + this class dir" JavaToGpuPreparedLauncherBenchmark --iterations=1000
 * </pre>
 *
 * <p>For real runs, put this file into a Gradle/Maven test source set that already has JavaToGpu,
 * Packager, JavaParser, LWJGL OpenCL and platform LWJGL natives on the runtime classpath.</p>
 */
public final class JavaToGpuPreparedLauncherBenchmark {
    private static final int[] DEFAULT_POINTS = {128, 512, 2048, 8192, 32768};
    private static final double SCALE = 1.75D;
    private static final double BIAS = -32.0D;
    private static final double LIMIT = 4096.0D;
    private static final double EPSILON = 0.0D;

    private JavaToGpuPreparedLauncherBenchmark() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        System.out.println("JavaToGpu API: " + apiLocation());
        System.out.println("points=" + Arrays.toString(options.points)
                + ", warmup=" + options.warmup
                + ", iterations=" + options.iterations);
        System.out.println("points,prepareMs,coldMs,warmFirstMs,avgMs,minMs,p50Ms,p95Ms,maxMs,cpuMs,maxAbsError,firstGpu,firstCpu");

        GpuRuntimeCompileOptions compileOptions = GpuRuntimeCompileOptions
                .defaults(GpuBackendTarget.OPENCL)
                .withoutBackendDevicePreflight();

        try {
            try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
                for (int points : options.points) {
                    Result result = runOne(points, options.warmup, options.iterations, compileOptions);
                    System.out.println(result.toCsv());
                }
            }
        } finally {
            JavaToGpu.shutdownOpenClSharedCache();
        }
    }

    private static Result runOne(
            int points,
            int warmup,
            int iterations,
            GpuRuntimeCompileOptions compileOptions) {
        double[] input = new double[points];
        double[] output = new double[points];
        double[] expected = new double[points];
        fillInput(input);

        long cpuStart = System.nanoTime();
        computeCpu(input, expected);
        long cpuNanos = System.nanoTime() - cpuStart;

        long prepareStart = System.nanoTime();
        try (GpuPreparedLauncher launcher = JavaToGpu.prepareWithConfigAndCompileOptions(
                TestKernel.class,
                "transform",
                JavaToGpu.launch1D(points),
                compileOptions,
                input,
                SCALE,
                BIAS,
                LIMIT,
                output).withoutHostUploadArgumentNames("output")) {
            long prepareNanos = System.nanoTime() - prepareStart;

            long coldStart = System.nanoTime();
            launcher.invoke(input, SCALE, BIAS, LIMIT, output);
            long coldNanos = System.nanoTime() - coldStart;
            assertParity(points, "cold", output, expected);

            long warmFirstStart = System.nanoTime();
            launcher.invoke(input, SCALE, BIAS, LIMIT, output);
            long warmFirstNanos = System.nanoTime() - warmFirstStart;
            assertParity(points, "warm-first", output, expected);

            for (int i = 0; i < warmup; i++) {
                launcher.invoke(input, SCALE, BIAS, LIMIT, output);
            }

            long[] samples = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                launcher.invoke(input, SCALE, BIAS, LIMIT, output);
                samples[i] = System.nanoTime() - start;
            }
            assertParity(points, "measured", output, expected);

            return Result.from(points, prepareNanos, coldNanos, warmFirstNanos, samples, cpuNanos, output, expected);
        }
    }

    private static void fillInput(double[] input) {
        for (int i = 0; i < input.length; i++) {
            input[i] = ((i & 1023) - 512) * 0.03125D + ((i >>> 10) & 31);
        }
    }

    private static void computeCpu(double[] input, double[] output) {
        for (int i = 0; i < output.length; i++) {
            output[i] = transformCpu(input[i]);
        }
    }

    private static double transformCpu(double value) {
        double a = value * SCALE + BIAS;
        double b = a * a - value * 0.125D;
        double c = b < 0.0D ? -b : b;
        return c > LIMIT ? LIMIT : c;
    }

    private static void assertParity(int points, String stage, double[] gpu, double[] expected) {
        double maxAbsError = maxAbsError(gpu, expected);
        if (maxAbsError > EPSILON) {
            throw new AssertionError(points + " points " + stage + " parity failed: maxAbsError=" + maxAbsError
                    + ", firstGpu=" + gpu[0] + ", firstCpu=" + expected[0]);
        }
    }

    private static double maxAbsError(double[] gpu, double[] expected) {
        double max = 0.0D;
        for (int i = 0; i < gpu.length; i++) {
            double diff = gpu[i] - expected[i];
            double abs = diff < 0.0D ? -diff : diff;
            if (abs > max) {
                max = abs;
            }
        }
        return max;
    }

    private static String apiLocation() {
        CodeSource codeSource = JavaToGpu.class.getProtectionDomain().getCodeSource();
        return codeSource == null || codeSource.getLocation() == null ? "unknown" : codeSource.getLocation().toString();
    }

    public static final class TestKernel {
        private TestKernel() {
        }

        @net.sixik.ga_utils.javatogpu.api.annotations.GPU
        public static void transform(
                @GPUGlobal(constant = true) double[] input,
                double scale,
                double bias,
                double limit,
                @GPUGlobal double[] output) {
            int point = GPU.get_global_id(0);
            double value = input[point];
            double a = value * scale + bias;
            double b = a * a - value * 0.125D;
            double c = b < 0.0D ? -b : b;
            output[point] = c > limit ? limit : c;
        }
    }

    private record Options(int[] points, int warmup, int iterations) {
        private static Options parse(String[] args) {
            int[] points = DEFAULT_POINTS;
            int warmup = 32;
            int iterations = 256;

            for (String arg : args) {
                if (arg.startsWith("--points=")) {
                    points = parsePoints(arg.substring("--points=".length()));
                } else if (arg.startsWith("--warmup=")) {
                    warmup = parsePositiveInt("warmup", arg.substring("--warmup=".length()));
                } else if (arg.startsWith("--iterations=")) {
                    iterations = parsePositiveInt("iterations", arg.substring("--iterations=".length()));
                } else if (arg.equals("--help") || arg.equals("-h")) {
                    printHelpAndExit();
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            return new Options(points, warmup, iterations);
        }

        private static int[] parsePoints(String text) {
            String[] parts = text.split(",");
            int[] parsed = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                parsed[i] = parsePositiveInt("points", parts[i].trim());
            }
            return parsed;
        }

        private static int parsePositiveInt(String name, String text) {
            int value = Integer.parseInt(text);
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive: " + value);
            }
            return value;
        }

        private static void printHelpAndExit() {
            System.out.println("Usage: JavaToGpuPreparedLauncherBenchmark [--points=128,512,2048] [--warmup=32] [--iterations=256]");
            System.exit(0);
        }
    }

    private record Result(
            int points,
            long prepareNanos,
            long coldNanos,
            long warmFirstNanos,
            double avgNanos,
            long minNanos,
            long p50Nanos,
            long p95Nanos,
            long maxNanos,
            long cpuNanos,
            double maxAbsError,
            double firstGpu,
            double firstCpu) {
        private static Result from(
                int points,
                long prepareNanos,
                long coldNanos,
                long warmFirstNanos,
                long[] samples,
                long cpuNanos,
                double[] output,
                double[] expected) {
            long[] sorted = samples.clone();
            Arrays.sort(sorted);
            long total = 0L;
            for (long sample : samples) {
                total += sample;
            }
            return new Result(
                    points,
                    prepareNanos,
                    coldNanos,
                    warmFirstNanos,
                    (double) total / (double) samples.length,
                    sorted[0],
                    sorted[sorted.length / 2],
                    sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95D) - 1)],
                    sorted[sorted.length - 1],
                    cpuNanos,
                    JavaToGpuPreparedLauncherBenchmark.maxAbsError(output, expected),
                    output.length == 0 ? Double.NaN : output[0],
                    expected.length == 0 ? Double.NaN : expected[0]);
        }

        private String toCsv() {
            return String.format(Locale.ROOT,
                    "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3g,%.17g,%.17g",
                    points,
                    toMillis(prepareNanos),
                    toMillis(coldNanos),
                    toMillis(warmFirstNanos),
                    toMillis(avgNanos),
                    toMillis(minNanos),
                    toMillis(p50Nanos),
                    toMillis(p95Nanos),
                    toMillis(maxNanos),
                    toMillis(cpuNanos),
                    maxAbsError,
                    firstGpu,
                    firstCpu);
        }

        private static double toMillis(long nanos) {
            return nanos / 1_000_000.0D;
        }

        private static double toMillis(double nanos) {
            return nanos / 1_000_000.0D;
        }
    }
}
