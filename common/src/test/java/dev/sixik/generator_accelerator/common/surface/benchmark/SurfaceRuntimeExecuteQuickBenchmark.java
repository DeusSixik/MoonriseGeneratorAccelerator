package dev.sixik.generator_accelerator.common.surface.benchmark;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.surface.mixin.GABiomeManagerAccess;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public final class SurfaceRuntimeExecuteQuickBenchmark {
    private static volatile long sink;

    private SurfaceRuntimeExecuteQuickBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Options options = Options.parse(args);
        SurfaceRules.RuleSource directRule = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource templateRule = SurfaceRules.sequence(
                SurfaceRules.ifTrue(
                        SurfaceRules.not(SurfaceRules.waterBlockCheck(-1, 0)),
                        SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState())
                ),
                SurfaceRules.state(Blocks.STONE.defaultBlockState())
        );
        SurfaceExecutionPlan directPlan = SurfaceRuntime.prepare(directRule);
        SurfaceExecutionPlan templatePlan = SurfaceRuntime.prepare(templateRule);
        RuntimeFixture directFixture = RuntimeFixture.create();
        RuntimeFixture templateFixture = RuntimeFixture.create();

        System.out.printf(Locale.ROOT,
                "surface.runtime.execute warmup=%d iterations=%d samples=%d reset_included=true direct_tier=%s direct_commit=%s template_tier=%s template_commit=%s%n",
                options.warmup,
                options.iterations,
                options.samples,
                directPlan.tier(),
                directPlan.commitMode(),
                templatePlan.tier(),
                templatePlan.commitMode());

        for (int i = 0; i < options.warmup; i++) {
            sink += executeDirect(directPlan, directRule, directFixture);
            sink += executeTemplate(templatePlan, templateRule, templateFixture);
        }

        long[] directNanos = new long[options.samples];
        long[] templateNanos = new long[options.samples];
        for (int sample = 0; sample < options.samples; sample++) {
            long directStart = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                sink += executeDirect(directPlan, directRule, directFixture);
            }
            long directElapsed = System.nanoTime() - directStart;

            long templateStart = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                sink += executeTemplate(templatePlan, templateRule, templateFixture);
            }
            long templateElapsed = System.nanoTime() - templateStart;

            directNanos[sample] = directElapsed;
            templateNanos[sample] = templateElapsed;
            System.out.printf(Locale.ROOT,
                    "sample=%d direct_execute_ms=%.3f template_execute_ms=%.3f direct_execute_ns_per_chunk=%.1f template_execute_ns_per_chunk=%.1f template_over_direct=%.2fx%n",
                    sample + 1,
                    directElapsed / 1_000_000.0,
                    templateElapsed / 1_000_000.0,
                    directElapsed / (double) options.iterations,
                    templateElapsed / (double) options.iterations,
                    templateElapsed / (double) Math.max(1L, directElapsed));
        }

        Arrays.sort(directNanos);
        Arrays.sort(templateNanos);
        System.out.printf(Locale.ROOT,
                "result direct_best_ns_per_chunk=%.1f direct_median_ns_per_chunk=%.1f direct_worst_ns_per_chunk=%.1f direct_p95_ns_per_chunk=%.1f "
                        + "template_best_ns_per_chunk=%.1f template_median_ns_per_chunk=%.1f template_worst_ns_per_chunk=%.1f template_p95_ns_per_chunk=%.1f "
                        + "template_median_over_direct=%.2fx sink=%d%n",
                directNanos[0] / (double) options.iterations,
                directNanos[directNanos.length / 2] / (double) options.iterations,
                directNanos[directNanos.length - 1] / (double) options.iterations,
                percentile(directNanos, 0.95) / (double) options.iterations,
                templateNanos[0] / (double) options.iterations,
                templateNanos[templateNanos.length / 2] / (double) options.iterations,
                templateNanos[templateNanos.length - 1] / (double) options.iterations,
                percentile(templateNanos, 0.95) / (double) options.iterations,
                templateNanos[templateNanos.length / 2] / (double) Math.max(1L, directNanos[directNanos.length / 2]),
                sink);
    }

    private static long executeDirect(SurfaceExecutionPlan plan, SurfaceRules.RuleSource rule, RuntimeFixture fixture) {
        fixture.resetStone();
        boolean executed = SurfaceRuntime.execute(
                plan,
                fixture.surfaceSystem,
                null,
                fixture.biomeManager,
                null,
                false,
                null,
                fixture.chunk,
                fixture.noiseChunk,
                rule
        );
        if (!executed) {
            throw new IllegalStateException("direct SurfaceRuntime.execute returned false");
        }
        return fixture.raw[fixture.raw.length - 1];
    }

    private static long executeTemplate(SurfaceExecutionPlan plan, SurfaceRules.RuleSource rule, RuntimeFixture fixture) {
        fixture.resetStone();
        boolean executed = SurfaceRuntime.execute(
                plan,
                fixture.surfaceSystem,
                null,
                fixture.biomeManager,
                null,
                false,
                null,
                fixture.chunk,
                fixture.noiseChunk,
                rule
        );
        if (!executed) {
            throw new IllegalStateException("template SurfaceRuntime.execute returned false");
        }
        return fixture.raw[fixture.raw.length - 1];
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private record Options(int warmup, int iterations, int samples) {
        private static Options parse(String[] args) {
            int warmup = 250;
            int iterations = 1000;
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

    private static final class RuntimeFixture {
        private final int[] raw;
        private final ChunkAccess chunk;
        private final SurfaceSystem surfaceSystem;
        private final BiomeManager biomeManager;
        private final NoiseChunk noiseChunk;
        private final int stoneId;

        private RuntimeFixture(
                int[] raw,
                ChunkAccess chunk,
                SurfaceSystem surfaceSystem,
                BiomeManager biomeManager,
                NoiseChunk noiseChunk,
                int stoneId
        ) {
            this.raw = raw;
            this.chunk = chunk;
            this.surfaceSystem = surfaceSystem;
            this.biomeManager = biomeManager;
            this.noiseChunk = noiseChunk;
            this.stoneId = stoneId;
        }

        private void resetStone() {
            Arrays.fill(this.raw, this.stoneId);
        }

        private static RuntimeFixture create() throws Exception {
            int stoneId = Block.getId(Blocks.STONE.defaultBlockState());
            int[] raw = new int[4096];
            Arrays.fill(raw, stoneId);

            LevelChunkSection section = mock(LevelChunkSection.class,
                    withSettings().extraInterfaces(LevelChunkSection$FlatBlockArray.class));
            when(section.hasOnlyAir()).thenReturn(false);
            when(((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData()).thenReturn(raw);
            when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                    .thenAnswer(invocation -> {
                        System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                        return true;
                    });

            ChunkAccess chunk = mock(ChunkAccess.class);
            when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
            when(chunk.getMinBuildHeight()).thenReturn(0);
            when(chunk.getSection(0)).thenReturn(section);
            when(chunk.getSections()).thenReturn(new LevelChunkSection[]{section});
            when(chunk.getSectionYFromSectionIndex(0)).thenReturn(0);
            when(chunk.getHeight(eq(Heightmap.Types.WORLD_SURFACE_WG), anyInt(), anyInt())).thenReturn(15);
            for (Heightmap.Types type : Heightmap.Types.values()) {
                when(chunk.getOrCreateHeightmapUnprimed(type)).thenReturn(mock(Heightmap.class));
            }

            SurfaceSystem surfaceSystem = surfaceSystemWithDefaultBlock(Blocks.STONE.defaultBlockState());
            BiomeManager biomeManager = biomeManager();
            NoiseChunk noiseChunk = mock(NoiseChunk.class);
            when(noiseChunk.preliminarySurfaceLevel(anyInt(), anyInt())).thenReturn(15);
            return new RuntimeFixture(raw, chunk, surfaceSystem, biomeManager, noiseChunk, stoneId);
        }

        private static SurfaceSystem surfaceSystemWithDefaultBlock(BlockState defaultBlock) throws Exception {
            SurfaceSystem surfaceSystem = mock(SurfaceSystem.class);
            Field field = SurfaceSystem.class.getDeclaredField("defaultBlock");
            field.setAccessible(true);
            field.set(surfaceSystem, defaultBlock);
            when(surfaceSystem.getSurfaceDepth(anyInt(), anyInt())).thenReturn(0);
            when(surfaceSystem.getSurfaceSecondary(anyInt(), anyInt())).thenReturn(0.0);
            return surfaceSystem;
        }

        @SuppressWarnings("unchecked")
        private static BiomeManager biomeManager() {
            Holder<Biome> biome = mock(Holder.class);
            when(biome.is(any(ResourceKey.class))).thenReturn(false);

            BiomeManager biomeManager = mock(BiomeManager.class,
                    withSettings().extraInterfaces(GABiomeManagerAccess.class));
            when(((GABiomeManagerAccess) (Object) biomeManager).bts$getBiomeZoomSeed()).thenReturn(0L);
            when(((GABiomeManagerAccess) (Object) biomeManager).bts$getNoiseBiomeSource()).thenReturn((x, y, z) -> biome);
            when(biomeManager.getBiome(any(BlockPos.class))).thenReturn(biome);
            return biomeManager;
        }
    }
}
