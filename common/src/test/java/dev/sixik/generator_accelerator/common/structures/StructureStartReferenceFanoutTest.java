package dev.sixik.generator_accelerator.common.structures;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class StructureStartReferenceFanoutTest {
    private static volatile int sink;
    private static final int DEFAULT_BENCHMARK_REFERENCES = 64;
    private static final int DEFAULT_BENCHMARK_WARMUP = 256;
    private static final int DEFAULT_BENCHMARK_ITERATIONS = 1_000;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void optimizedFanoutMatchesVanilla() {
        TestCase testCase = createCase(192);
        List<StructureStart> expected = new ArrayList<>();
        List<StructureStart> actual = new ArrayList<>();

        vanillaFillStarts(testCase.level, testCase.structure, testCase.references, expected::add);
        StructureStartReferenceFanout.fillStarts(testCase.level, testCase.structure, testCase.references, actual::add);

        assertEquals(expected, actual);
    }

    @Test
    void printsStructureStartFanoutMetrics() {
        int warmup = Integer.getInteger("ga.test.structureStartFanoutWarmup", DEFAULT_BENCHMARK_WARMUP);
        int iterations = Integer.getInteger("ga.test.structureStartFanoutIterations", DEFAULT_BENCHMARK_ITERATIONS);
        int references = Integer.getInteger("ga.test.structureStartFanoutReferences", DEFAULT_BENCHMARK_REFERENCES);
        TestCase testCase = createCase(references);

        System.out.println("StructureManager reference fan-out benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", references=" + testCase.references.size());
        // Keep common:test responsive: this harness uses Mockito-backed Minecraft objects,
        // so large local benchmark runs must opt in via the ga.test.structureStartFanout* properties.
        runVanilla(testCase, warmup);
        runOptimized(testCase, warmup);
        long vanillaNanos = timeVanilla(testCase, iterations);
        long optimizedNanos = timeOptimized(testCase, iterations);

        printMetric("fill-starts", vanillaNanos, optimizedNanos, iterations);
    }

    private static long timeVanilla(TestCase testCase, int iterations) {
        long started = System.nanoTime();
        runVanilla(testCase, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanilla(TestCase testCase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += countVanilla(testCase);
        }
        sink = local;
    }

    private static int countVanilla(TestCase testCase) {
        int[] count = {0};
        vanillaFillStarts(testCase.level, testCase.structure, testCase.references, start -> count[0]++);
        return count[0];
    }

    private static long timeOptimized(TestCase testCase, int iterations) {
        long started = System.nanoTime();
        runOptimized(testCase, iterations);
        return System.nanoTime() - started;
    }

    private static void runOptimized(TestCase testCase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += countOptimized(testCase);
        }
        sink = local;
    }

    private static int countOptimized(TestCase testCase) {
        int[] count = {0};
        StructureStartReferenceFanout.fillStarts(testCase.level, testCase.structure, testCase.references, start -> count[0]++);
        return count[0];
    }

    private static void vanillaFillStarts(LevelAccessor level, Structure structure, LongSet references, java.util.function.Consumer<StructureStart> consumer) {
        LongIterator iterator = references.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(packed);
            int minSection = level.getMinSection();
            StructureStart start = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure);
            if (start != null && start.isValid()) {
                consumer.accept(start);
            }
            sink ^= minSection;
        }
    }

    private static TestCase createCase(int referenceCount) {
        LevelAccessor level = Mockito.mock(LevelAccessor.class, Mockito.withSettings().stubOnly());
        Structure structure = new DummyStructure();
        LongArraySet references = new LongArraySet(referenceCount);
        java.util.HashMap<Long, ChunkAccess> chunks = new java.util.HashMap<>(referenceCount * 2);

        Mockito.when(level.getMinSection()).thenReturn(-4);
        Mockito.when(level.getChunk(anyInt(), anyInt(), eq(ChunkStatus.STRUCTURE_STARTS))).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int z = invocation.getArgument(1);
            return chunks.get(ChunkPos.asLong(x, z));
        });

        for (int i = 0; i < referenceCount; i++) {
            int x = i * 3 - 97;
            int z = i * 5 + 31;
            long packed = ChunkPos.asLong(x, z);
            references.add(packed);

            ChunkAccess chunk = Mockito.mock(ChunkAccess.class, Mockito.withSettings().stubOnly());
            StructureStart start = Mockito.mock(StructureStart.class, Mockito.withSettings().stubOnly());
            boolean valid = (i & 3) != 0;
            Mockito.when(start.isValid()).thenReturn(valid);
            Mockito.when(chunk.getStartForStructure(structure)).thenReturn(valid ? start : null);
            chunks.put(packed, chunk);
        }

        return new TestCase(level, structure, references);
    }

    private static void printMetric(String label, long previousNanos, long nextNanos, int iterations) {
        double previousNsOp = (double) previousNanos / iterations;
        double nextNsOp = (double) nextNanos / iterations;
        double speedup = previousNsOp / Math.max(1.0D, nextNsOp);
        System.out.printf("%s vanilla=%.1f ns/op optimized=%.1f ns/op speedup=%.2fx%n",
                label, previousNsOp, nextNsOp, speedup);
    }

    private record TestCase(LevelAccessor level, Structure structure, LongSet references) {
    }

    private static final class DummyStructure extends Structure {
        private DummyStructure() {
            super(new Structure.StructureSettings(
                    HolderSet.direct(Holder.direct(Mockito.mock(net.minecraft.world.level.biome.Biome.class, Mockito.withSettings().stubOnly()))),
                    Map.<MobCategory, StructureSpawnOverride>of(),
                    GenerationStep.Decoration.SURFACE_STRUCTURES,
                    TerrainAdjustment.NONE
            ));
        }

        @Override
        protected Optional<GenerationStub> findGenerationPoint(GenerationContext generationContext) {
            return Optional.empty();
        }

        @Override
        public StructureType<?> type() {
            return null;
        }
    }
}
