package dev.sixik.generator_accelerator.common.surface.benchmark;

import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceCompilerConfig;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceProgram;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceRuleCompiler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

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
        SurfaceRules.RuleSource[] suite = buildRuleSuite();
        System.out.printf(Locale.ROOT,
                "surface.quick ir=%s metrics=%s rules=%d warmup=%d iterations=%d samples=%d%n",
                SurfaceCompilerConfig.IR,
                SurfaceCompilerConfig.METRICS,
                suite.length,
                options.warmup,
                options.iterations,
                options.samples);
        printPlanShape(suite);

        for (int i = 0; i < options.warmup; i++) {
            compileSuite(suite);
        }

        long[] sampleNanos = new long[options.samples];
        int operationsPerSample = options.iterations * suite.length;
        for (int sample = 0; sample < options.samples; sample++) {
            long start = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                compileSuite(suite);
            }
            long elapsed = System.nanoTime() - start;
            sampleNanos[sample] = elapsed;
            System.out.printf(Locale.ROOT,
                    "sample=%d total_ms=%.3f ns_per_rule=%.1f compiles_per_sec=%.0f%n",
                    sample + 1,
                    elapsed / 1_000_000.0,
                    elapsed / (double) operationsPerSample,
                    operationsPerSample * 1_000_000_000.0 / elapsed);
        }

        Arrays.sort(sampleNanos);
        long best = sampleNanos[0];
        long median = sampleNanos[sampleNanos.length / 2];
        long worst = sampleNanos[sampleNanos.length - 1];
        System.out.printf(Locale.ROOT,
                "result best_ns_per_rule=%.1f median_ns_per_rule=%.1f worst_ns_per_rule=%.1f sink=%d%n",
                best / (double) operationsPerSample,
                median / (double) operationsPerSample,
                worst / (double) operationsPerSample,
                sink);
    }

    private static void printPlanShape(SurfaceRules.RuleSource[] suite) {
        long opcodes = 0L;
        long testBlocks = 0L;
        long genericRules = 0L;
        long requirements = 0L;
        long fallbacks = 0L;
        for (SurfaceRules.RuleSource rule : suite) {
            SurfaceProgram program = SurfaceRuleCompiler.compile(rule);
            opcodes += program.opcodeCount();
            testBlocks += program.testBlockOpcodeCount();
            genericRules += program.genericRuleOpcodeCount();
            requirements |= program.requirements();
            fallbacks += program.fallbackIslandCount();
            consume(program);
        }
        System.out.printf(Locale.ROOT,
                "shape opcodes=%d test_block_opcodes=%d generic_rule_opcodes=%d requirements=0x%X fallbacks=%d%n",
                opcodes,
                testBlocks,
                genericRules,
                requirements,
                fallbacks);
    }

    private static void compileSuite(SurfaceRules.RuleSource[] suite) {
        for (SurfaceRules.RuleSource rule : suite) {
            consume(SurfaceRuleCompiler.compile(rule));
        }
    }

    private static void consume(SurfaceProgram program) {
        sink += program.requirements()
                + program.opcodeCount()
                + program.testBlockOpcodeCount() * 17L
                + program.genericRuleOpcodeCount() * 31L
                + program.fallbackIslandCount() * 43L
                + (program.mayWriteFluid() ? 59L : 0L);
    }

    private static SurfaceRules.RuleSource[] buildRuleSuite() {
        SurfaceRules.ConditionSource floor = SurfaceRules.stoneDepthCheck(0, false, CaveSurface.FLOOR);
        SurfaceRules.ConditionSource deepFloor = SurfaceRules.stoneDepthCheck(2, true, 3, CaveSurface.FLOOR);
        SurfaceRules.ConditionSource water = SurfaceRules.waterBlockCheck(-1, 0);
        SurfaceRules.ConditionSource yHigh = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(96), 0);
        SurfaceRules.ConditionSource yLow = SurfaceRules.yStartCheck(VerticalAnchor.aboveBottom(48), 1);
        SurfaceRules.ConditionSource surfaceNoise = SurfaceRules.noiseCondition(Noises.SURFACE, -0.35D, 0.42D);
        SurfaceRules.ConditionSource secondaryNoise = SurfaceRules.noiseCondition(Noises.SURFACE_SECONDARY, 0.08D);
        SurfaceRules.ConditionSource badlands = SurfaceRules.isBiome(Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);
        SurfaceRules.ConditionSource desert = SurfaceRules.isBiome(Biomes.DESERT, Biomes.BEACH);
        SurfaceRules.ConditionSource cold = SurfaceRules.isBiome(Biomes.SNOWY_PLAINS, Biomes.FROZEN_OCEAN, Biomes.SNOWY_TAIGA);

        SurfaceRules.RuleSource grass = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource dirt = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        SurfaceRules.RuleSource sand = SurfaceRules.state(Blocks.SAND.defaultBlockState());
        SurfaceRules.RuleSource redSand = SurfaceRules.state(Blocks.RED_SAND.defaultBlockState());
        SurfaceRules.RuleSource gravel = SurfaceRules.state(Blocks.GRAVEL.defaultBlockState());
        SurfaceRules.RuleSource snow = SurfaceRules.state(Blocks.SNOW_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());

        SurfaceRules.RuleSource vanillaLike = SurfaceRules.sequence(
                SurfaceRules.ifTrue(badlands, SurfaceRules.ifTrue(surfaceNoise, redSand)),
                SurfaceRules.ifTrue(desert, SurfaceRules.ifTrue(floor, sand)),
                SurfaceRules.ifTrue(cold, SurfaceRules.ifTrue(yHigh, snow)),
                SurfaceRules.ifTrue(SurfaceRules.not(water), SurfaceRules.ifTrue(floor, grass)),
                SurfaceRules.ifTrue(deepFloor, dirt),
                stone
        );

        SurfaceRules.RuleSource repeatedIdentity = SurfaceRules.sequence(
                SurfaceRules.ifTrue(floor, grass),
                SurfaceRules.ifTrue(floor, dirt),
                SurfaceRules.ifTrue(floor, sand),
                SurfaceRules.ifTrue(SurfaceRules.not(floor), gravel),
                SurfaceRules.ifTrue(floor, stone)
        );

        SurfaceRules.RuleSource structuralDuplicates = SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.stoneDepthCheck(0, false, CaveSurface.FLOOR), grass),
                SurfaceRules.ifTrue(SurfaceRules.stoneDepthCheck(0, false, CaveSurface.FLOOR), dirt),
                SurfaceRules.ifTrue(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(96), 0), sand),
                SurfaceRules.ifTrue(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(96), 0), gravel),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.35D, 0.42D), redSand),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.35D, 0.42D), stone)
        );

        SurfaceRules.RuleSource nestedTests = SurfaceRules.ifTrue(floor,
                SurfaceRules.ifTrue(SurfaceRules.not(water),
                        SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(),
                                SurfaceRules.ifTrue(surfaceNoise,
                                        SurfaceRules.ifTrue(yLow, grass)))));

        SurfaceRules.RuleSource mixed = SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.verticalGradient("ga_quick_a", VerticalAnchor.absolute(64), VerticalAnchor.absolute(96)), dirt),
                SurfaceRules.ifTrue(SurfaceRules.temperature(), snow),
                SurfaceRules.ifTrue(SurfaceRules.steep(), gravel),
                SurfaceRules.ifTrue(SurfaceRules.hole(), stone),
                SurfaceRules.ifTrue(secondaryNoise, SurfaceRules.bandlands()),
                vanillaLike
        );

        return new SurfaceRules.RuleSource[]{
                SurfaceRules.state(Blocks.STONE.defaultBlockState()),
                vanillaLike,
                repeatedIdentity,
                structuralDuplicates,
                nestedTests,
                mixed
        };
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
