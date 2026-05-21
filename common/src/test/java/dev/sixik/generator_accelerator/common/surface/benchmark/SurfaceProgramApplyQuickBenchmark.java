package dev.sixik.generator_accelerator.common.surface.benchmark;

import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceCompilerConfig;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceProgram;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceRuleCompiler;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceScratch;
import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import com.mojang.datafixers.util.Either;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class SurfaceProgramApplyQuickBenchmark {
    private static volatile long sink;

    private SurfaceProgramApplyQuickBenchmark() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Options options = Options.parse(args);
        SurfaceRules.RuleSource rule = buildRule();
        SurfaceProgram program = SurfaceRuleCompiler.compile(rule);
        Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[256];
        for (int xz = 0; xz < 256; xz++) {
            biomes[xz] = new TestBiomeHolder((xz & 1) == 0 ? Biomes.PLAINS : Biomes.DESERT);
        }
        RandomState randomState = createRandomState();
        VectorChunkContext[] contexts = createContexts(biomes, randomState);

        int[] rawBlockData = new int[4096];
        SurfaceScratch scratch = new SurfaceScratch();
        Mask4096 stoneMask = new Mask4096();
        stoneMask.fill();

        System.out.printf(Locale.ROOT,
                "surface.apply ir=%s metrics=%s opcodes=%d test_block_opcodes=%d regional_cache=%s warmup=%d iterations=%d samples=%d%n",
                SurfaceCompilerConfig.IR,
                SurfaceCompilerConfig.METRICS,
                program.opcodeCount(),
                program.testBlockOpcodeCount(),
                Boolean.parseBoolean(System.getProperty("ga.surface.regionalNoiseCache.enabled", "true")),
                options.warmup,
                options.iterations,
                options.samples);

        for (int i = 0; i < options.warmup; i++) {
            runOnce(program, rawBlockData, stoneMask, contexts[i & 15], scratch);
        }

        long[] sampleNanos = new long[options.samples];
        for (int sample = 0; sample < options.samples; sample++) {
            long start = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                runOnce(program, rawBlockData, stoneMask, contexts[i & 15], scratch);
            }
            long elapsed = System.nanoTime() - start;
            sampleNanos[sample] = elapsed;
            System.out.printf(Locale.ROOT,
                    "sample=%d total_ms=%.3f ns_per_apply=%.1f applies_per_sec=%.0f%n",
                    sample + 1,
                    elapsed / 1_000_000.0,
                    elapsed / (double) options.iterations,
                    options.iterations * 1_000_000_000.0 / elapsed);
        }

        Arrays.sort(sampleNanos);
        System.out.printf(Locale.ROOT,
                "result best_ns_per_apply=%.1f median_ns_per_apply=%.1f worst_ns_per_apply=%.1f sink=%d%n",
                sampleNanos[0] / (double) options.iterations,
                sampleNanos[sampleNanos.length / 2] / (double) options.iterations,
                sampleNanos[sampleNanos.length - 1] / (double) options.iterations,
                sink);
    }

    private static void runOnce(SurfaceProgram program, int[] rawBlockData, Mask4096 stoneMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        Arrays.fill(rawBlockData, ctx.STONE_ID);
        program.apply(rawBlockData, stoneMask, ctx, scratch);
        sink += rawBlockData[0] + rawBlockData[4095];
    }

    private static VectorChunkContext[] createContexts(Holder<Biome>[] biomes, RandomState randomState) {
        VectorChunkContext[] contexts = new VectorChunkContext[16];
        int defaultBlockId = Block.getId(Blocks.STONE.defaultBlockState());
        for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
            for (int chunkX = 0; chunkX < 4; chunkX++) {
                VectorChunkContext ctx = new VectorChunkContext(biomes, defaultBlockId, null, randomState, null);
                ctx.updateForSection(chunkX << 4, 64, chunkZ << 4);
                fillContext(ctx);
                contexts[(chunkZ << 2) | chunkX] = ctx;
            }
        }
        return contexts;
    }

    private static void fillContext(VectorChunkContext ctx) {
        for (int xz = 0; xz < 256; xz++) {
            ctx.surfaceDepths[xz] = (xz & 1) == 0 ? 0 : 3;
            ctx.surfaceHeights[xz] = (short) (((xz >> 4) & 1) == 0 ? 64 : 70);
        }
        Arrays.fill(ctx.secondarySurfaceNoises, 0.25D);
        Arrays.fill(ctx.minSurfaceLevels, 68);
        Arrays.fill(ctx.waterHeights, Integer.MIN_VALUE);
        for (int i = 0; i < 4096; i++) {
            int y = i >> 8;
            ctx.stoneDepthAbove[i] = (byte) (16 - y);
            ctx.stoneDepthBelow[i] = (byte) (y + 1);
        }
    }

    private static SurfaceRules.RuleSource buildRule() {
        SurfaceRules.ConditionSource surfaceNoise = SurfaceRules.noiseCondition(Noises.SURFACE, -0.35D, 0.42D);
        SurfaceRules.ConditionSource secondaryNoise = SurfaceRules.noiseCondition(Noises.SURFACE_SECONDARY, 0.08D);
        SurfaceRules.ConditionSource y66 = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(66), 0);
        SurfaceRules.ConditionSource y70 = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(70), 0);
        SurfaceRules.ConditionSource y72WithDepth = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(69), 1);
        SurfaceRules.ConditionSource abovePreliminary = SurfaceRules.abovePreliminarySurface();
        SurfaceRules.ConditionSource hole = SurfaceRules.hole();
        SurfaceRules.ConditionSource steep = SurfaceRules.steep();
        SurfaceRules.ConditionSource plains = SurfaceRules.isBiome(Biomes.PLAINS);
        SurfaceRules.ConditionSource notPlains = SurfaceRules.not(plains);
        SurfaceRules.RuleSource grass = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource dirt = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        SurfaceRules.RuleSource gravel = SurfaceRules.state(Blocks.GRAVEL.defaultBlockState());
        SurfaceRules.RuleSource sand = SurfaceRules.state(Blocks.SAND.defaultBlockState());
        SurfaceRules.RuleSource clay = SurfaceRules.state(Blocks.CLAY.defaultBlockState());
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(surfaceNoise, SurfaceRules.ifTrue(y70, sand)),
                SurfaceRules.ifTrue(secondaryNoise, gravel),
                SurfaceRules.ifTrue(plains, SurfaceRules.ifTrue(y70, clay)),
                SurfaceRules.ifTrue(notPlains, SurfaceRules.ifTrue(y70, sand)),
                SurfaceRules.ifTrue(hole, SurfaceRules.ifTrue(y70, sand)),
                SurfaceRules.ifTrue(steep, gravel),
                SurfaceRules.ifTrue(y70, grass),
                SurfaceRules.ifTrue(y66, dirt),
                SurfaceRules.ifTrue(abovePreliminary, gravel),
                SurfaceRules.ifTrue(y72WithDepth, stone),
                stone
        );
    }

    private static RandomState createRandomState() {
        RandomState randomState = Mockito.mock(RandomState.class, Mockito.withSettings().stubOnly());
        NormalNoise surfaceNoise = Mockito.mock(NormalNoise.class, Mockito.withSettings().stubOnly());
        NormalNoise secondaryNoise = Mockito.mock(NormalNoise.class, Mockito.withSettings().stubOnly());

        Mockito.when(randomState.getOrCreateNoise(Noises.SURFACE)).thenReturn(surfaceNoise);
        Mockito.when(randomState.getOrCreateNoise(Noises.SURFACE_SECONDARY)).thenReturn(secondaryNoise);
        Mockito.when(surfaceNoise.getValue(Mockito.anyDouble(), Mockito.eq(0.0D), Mockito.anyDouble()))
                .thenAnswer(invocation -> {
                    double x = invocation.getArgument(0, Double.class);
                    double z = invocation.getArgument(2, Double.class);
                    return Math.sin(x * 0.03125D) * 0.45D + Math.cos(z * 0.046875D) * 0.35D;
                });
        Mockito.when(secondaryNoise.getValue(Mockito.anyDouble(), Mockito.eq(0.0D), Mockito.anyDouble()))
                .thenAnswer(invocation -> {
                    double x = invocation.getArgument(0, Double.class);
                    double z = invocation.getArgument(2, Double.class);
                    return Math.cos(x * 0.0625D) * 0.4D - Math.sin(z * 0.0234375D) * 0.3D;
                });

        return randomState;
    }

    private record TestBiomeHolder(ResourceKey<Biome> key) implements Holder<Biome> {
        @Override
        public Biome value() {
            return null;
        }

        @Override
        public boolean isBound() {
            return true;
        }

        @Override
        public boolean is(ResourceLocation location) {
            return this.key.location().equals(location);
        }

        @Override
        public boolean is(ResourceKey<Biome> key) {
            return this.key.equals(key);
        }

        @Override
        public boolean is(Predicate<ResourceKey<Biome>> predicate) {
            return predicate.test(this.key);
        }

        @Override
        public boolean is(TagKey<Biome> tagKey) {
            return false;
        }

        @Override
        public boolean is(Holder<Biome> holder) {
            return holder.unwrapKey().filter(this.key::equals).isPresent();
        }

        @Override
        public Stream<TagKey<Biome>> tags() {
            return Stream.empty();
        }

        @Override
        public Either<ResourceKey<Biome>, Biome> unwrap() {
            return Either.left(this.key);
        }

        @Override
        public Optional<ResourceKey<Biome>> unwrapKey() {
            return Optional.of(this.key);
        }

        @Override
        public Kind kind() {
            return Kind.REFERENCE;
        }

        @Override
        public boolean canSerializeIn(HolderOwner<Biome> holderOwner) {
            return true;
        }
    }

    private record Options(int warmup, int iterations, int samples) {
        private static Options parse(String[] args) {
            int warmup = 3000;
            int iterations = 12000;
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
