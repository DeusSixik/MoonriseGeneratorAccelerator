package dev.sixik.generator_accelerator.common.surface.benchmark;

import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceMetrics;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.Arrays;
import java.util.Locale;

public final class SurfaceCompilerQuickBenchmark {
    private static volatile long sink;

    private SurfaceCompilerQuickBenchmark() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Options options = Options.parse(args);
        SurfaceRules.RuleSource[] suite = {
                SurfaceRules.state(Blocks.STONE.defaultBlockState()),
                SurfaceRules.state(Blocks.DIRT.defaultBlockState()),
                SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.waterBlockCheck(-1, 0)), SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState())),
                        SurfaceRules.state(Blocks.STONE.defaultBlockState()))
        };

        System.out.printf(Locale.ROOT,
                "surface.runtime.prepare rules=%d warmup=%d iterations=%d samples=%d%n",
                suite.length,
                options.warmup,
                options.iterations,
                options.samples);

        for (int i = 0; i < options.warmup; i++) {
            prepareSuite(suite);
        }

        long[] sampleNanos = new long[options.samples];
        int operationsPerSample = options.iterations * suite.length;
        for (int sample = 0; sample < options.samples; sample++) {
            long start = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                prepareSuite(suite);
            }
            long elapsed = System.nanoTime() - start;
            sampleNanos[sample] = elapsed;
            System.out.printf(Locale.ROOT,
                    "sample=%d total_ms=%.3f ns_per_prepare=%.1f prepares_per_sec=%.0f%n",
                    sample + 1,
                    elapsed / 1_000_000.0,
                    elapsed / (double) operationsPerSample,
                    operationsPerSample * 1_000_000_000.0 / elapsed);
        }

        Arrays.sort(sampleNanos);
        System.out.printf(Locale.ROOT,
                "result best_ns_per_prepare=%.1f median_ns_per_prepare=%.1f p95_ns_per_prepare=%.1f worst_ns_per_prepare=%.1f sink=%d metrics=%s%n",
                sampleNanos[0] / (double) operationsPerSample,
                sampleNanos[sampleNanos.length / 2] / (double) operationsPerSample,
                percentile(sampleNanos, 0.95) / (double) operationsPerSample,
                sampleNanos[sampleNanos.length - 1] / (double) operationsPerSample,
                sink,
                SurfaceMetrics.snapshot());
    }

    private static void prepareSuite(SurfaceRules.RuleSource[] suite) {
        for (SurfaceRules.RuleSource rule : suite) {
            consume(SurfaceRuntime.prepare(rule));
        }
    }

    private static void consume(SurfaceExecutionPlan plan) {
        sink += plan.tier().ordinal() * 17L + plan.commitMode().ordinal() * 31L + plan.fallbackReason().ordinal();
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private record Options(int warmup, int iterations, int samples) {
        private static Options parse(String[] args) {
            int warmup = 600;
            int iterations = 1400;
            int samples = 5;
            for (String arg : args) {
                if (arg.startsWith("--warmup=")) {
                    warmup = parsePositive(arg, "--warmup=");
                } else if (arg.startsWith("--iterations=")) {
                    iterations = parsePositive(arg, "--iterations=");
                } else if (arg.startsWith("--samples=")) {
                    samples = parsePositive(arg, "--samples=");
                }
            }
            return new Options(warmup, iterations, samples);
        }

        private static int parsePositive(String arg, String prefix) {
            int value = Integer.parseInt(arg.substring(prefix.length()));
            if (value <= 0) {
                throw new IllegalArgumentException(prefix + " must be positive");
            }
            return value;
        }
    }
}
