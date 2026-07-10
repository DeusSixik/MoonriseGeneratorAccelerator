package dev.sixik.generator_accelerator.common.surface.benchmark;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.surface_compiler.cow.CowSectionWriter;
import dev.sixik.generator_accelerator.common.surface_compiler.cow.SectionCowManager;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.Arrays;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public final class SurfaceProgramApplyQuickBenchmark {
    private static volatile long sink;

    private SurfaceProgramApplyQuickBenchmark() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Options options = Options.parse(args);
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(rule);
        ChunkFixture directFixture = ChunkFixture.create();
        ChunkFixture cowFixture = ChunkFixture.create();

        System.out.printf(Locale.ROOT,
                "surface.runtime.chunk_apply tier=%s commit=%s fallback=%s warmup=%d iterations=%d samples=%d writes=%d%n",
                plan.tier(),
                plan.commitMode(),
                plan.fallbackReason(),
                options.warmup,
                options.iterations,
                options.samples,
                options.writesPerChunk);

        for (int i = 0; i < options.warmup; i++) {
            sink += directRawApply(directFixture, options.writesPerChunk);
            sink += cowShadowApply(cowFixture, options.writesPerChunk);
            sink += cowCommitApply(cowFixture, options.writesPerChunk);
        }

        long[] directNanos = new long[options.samples];
        long[] shadowNanos = new long[options.samples];
        long[] commitNanos = new long[options.samples];
        for (int sample = 0; sample < options.samples; sample++) {
            long directStart = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                sink += directRawApply(directFixture, options.writesPerChunk);
            }
            long directElapsed = System.nanoTime() - directStart;

            long shadowStart = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                sink += cowShadowApply(cowFixture, options.writesPerChunk);
            }
            long shadowElapsed = System.nanoTime() - shadowStart;

            long commitStart = System.nanoTime();
            for (int i = 0; i < options.iterations; i++) {
                sink += cowCommitApply(cowFixture, options.writesPerChunk);
            }
            long commitElapsed = System.nanoTime() - commitStart;

            directNanos[sample] = directElapsed;
            shadowNanos[sample] = shadowElapsed;
            commitNanos[sample] = commitElapsed;
            System.out.printf(Locale.ROOT,
                    "sample=%d direct_ms=%.3f shadow_ms=%.3f commit_ms=%.3f direct_ns_per_chunk=%.1f shadow_ns_per_chunk=%.1f commit_ns_per_chunk=%.1f shadow_over_direct=%.2fx commit_over_direct=%.2fx%n",
                    sample + 1,
                    directElapsed / 1_000_000.0,
                    shadowElapsed / 1_000_000.0,
                    commitElapsed / 1_000_000.0,
                    directElapsed / (double) options.iterations,
                    shadowElapsed / (double) options.iterations,
                    commitElapsed / (double) options.iterations,
                    shadowElapsed / (double) Math.max(1L, directElapsed),
                    commitElapsed / (double) Math.max(1L, directElapsed));
        }

        Arrays.sort(directNanos);
        Arrays.sort(shadowNanos);
        Arrays.sort(commitNanos);
        System.out.printf(Locale.ROOT,
                "result direct_best_ns_per_chunk=%.1f direct_median_ns_per_chunk=%.1f direct_worst_ns_per_chunk=%.1f "
                        + "direct_p95_ns_per_chunk=%.1f shadow_best_ns_per_chunk=%.1f shadow_median_ns_per_chunk=%.1f shadow_worst_ns_per_chunk=%.1f "
                        + "shadow_p95_ns_per_chunk=%.1f commit_best_ns_per_chunk=%.1f commit_median_ns_per_chunk=%.1f commit_worst_ns_per_chunk=%.1f "
                        + "commit_p95_ns_per_chunk=%.1f shadow_median_over_direct=%.2fx commit_median_over_direct=%.2fx sink=%d%n",
                directNanos[0] / (double) options.iterations,
                directNanos[directNanos.length / 2] / (double) options.iterations,
                directNanos[directNanos.length - 1] / (double) options.iterations,
                percentile(directNanos, 0.95) / (double) options.iterations,
                shadowNanos[0] / (double) options.iterations,
                shadowNanos[shadowNanos.length / 2] / (double) options.iterations,
                shadowNanos[shadowNanos.length - 1] / (double) options.iterations,
                percentile(shadowNanos, 0.95) / (double) options.iterations,
                commitNanos[0] / (double) options.iterations,
                commitNanos[commitNanos.length / 2] / (double) options.iterations,
                commitNanos[commitNanos.length - 1] / (double) options.iterations,
                percentile(commitNanos, 0.95) / (double) options.iterations,
                shadowNanos[shadowNanos.length / 2] / (double) Math.max(1L, directNanos[directNanos.length / 2]),
                commitNanos[commitNanos.length / 2] / (double) Math.max(1L, directNanos[directNanos.length / 2]),
                sink);
    }

    private static long directRawApply(ChunkFixture fixture, int writes) {
        int dirt = Block.getId(Blocks.DIRT.defaultBlockState());
        long checksum = 0L;
        for (int i = 0; i < writes; i++) {
            int index = writeIndex(i);
            fixture.raw[index] = dirt;
            checksum += fixture.raw[index] + index;
        }
        return checksum;
    }

    private static long cowShadowApply(ChunkFixture fixture, int writes) {
        SectionCowManager manager = new SectionCowManager(fixture.chunk);
        CowSectionWriter writer = manager.writerForY(0);
        long checksum = writeCowShadow(writer, writes);
        manager.discard();
        return checksum + writer.rawCopy()[writeIndex(writes - 1)];
    }

    private static long cowCommitApply(ChunkFixture fixture, int writes) {
        SectionCowManager manager = new SectionCowManager(fixture.chunk);
        CowSectionWriter writer = manager.writerForY(0);
        long checksum = writeCowShadow(writer, writes);
        manager.commit();
        return checksum + fixture.raw[writeIndex(writes - 1)];
    }

    private static long writeCowShadow(CowSectionWriter writer, int writes) {
        long checksum = 0L;
        for (int i = 0; i < writes; i++) {
            int index = writeIndex(i);
            writer.setBlockState(index & 15, (index >>> 8) & 15, (index >>> 4) & 15, Blocks.DIRT.defaultBlockState());
            checksum += index;
        }
        return checksum;
    }

    private static int writeIndex(int ordinal) {
        return (ordinal * 37) & 4095;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private record Options(int warmup, int iterations, int samples, int writesPerChunk) {
        private static Options parse(String[] args) {
            int warmup = 500;
            int iterations = 2000;
            int samples = 5;
            int writesPerChunk = 256;
            for (String arg : args) {
                if (arg.startsWith("--warmup=")) {
                    warmup = parsePositive(arg, "--warmup=");
                } else if (arg.startsWith("--iterations=")) {
                    iterations = parsePositive(arg, "--iterations=");
                } else if (arg.startsWith("--samples=")) {
                    samples = parsePositive(arg, "--samples=");
                } else if (arg.startsWith("--writes=")) {
                    writesPerChunk = parsePositive(arg, "--writes=");
                }
            }
            return new Options(warmup, iterations, samples, writesPerChunk);
        }

        private static int parsePositive(String arg, String prefix) {
            int value = Integer.parseInt(arg.substring(prefix.length()));
            if (value <= 0) {
                throw new IllegalArgumentException(prefix + " must be positive");
            }
            return value;
        }
    }

    private static final class ChunkFixture {
        private final int[] raw;
        private final ChunkAccess chunk;

        private ChunkFixture(int[] raw, ChunkAccess chunk) {
            this.raw = raw;
            this.chunk = chunk;
        }

        private static ChunkFixture create() {
            int[] raw = new int[4096];
            LevelChunkSection section = mock(LevelChunkSection.class,
                    withSettings().extraInterfaces(LevelChunkSection$FlatBlockArray.class));
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
            for (Heightmap.Types type : Heightmap.Types.values()) {
                when(chunk.getOrCreateHeightmapUnprimed(type)).thenReturn(mock(Heightmap.class));
            }
            return new ChunkFixture(raw, chunk);
        }
    }
}
