package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class StructurePlacementShufflerTest {
    private static volatile int sink;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void matchesVanillaRotationShuffle() {
        for (long seed = 0; seed < 4096; seed++) {
            RandomSource expectedRandom = RandomSource.create(seed);
            RandomSource actualRandom = RandomSource.create(seed);

            List<Rotation> expected = Rotation.getShuffled(expectedRandom);
            List<Rotation> actual = StructurePlacementShuffler.shuffledRotations(actualRandom);

            assertEquals(expected, actual, "seed=" + seed);
            assertEquals(expectedRandom.nextLong(), actualRandom.nextLong(), "random state drift: seed=" + seed);
        }
    }

    @Test
    void matchesVanillaTemplateShuffleAndAddAllPath() {
        int[] sizes = {0, 1, 2, 3, 8, 17, 64, 257};

        for (int size : sizes) {
            StructurePoolElement[] elements = elements(size);
            for (long seed = 0; seed < 1024; seed++) {
                RandomSource expectedRandom = RandomSource.create(seed);
                RandomSource actualRandom = RandomSource.create(seed);

                List<StructurePoolElement> expected = Util.shuffledCopy(elements, expectedRandom);
                List<StructurePoolElement> actual = StructurePlacementShuffler.shuffledTemplates(elements, actualRandom);

                assertSameOrder(expected, actual, "direct: size=" + size + ", seed=" + seed);

                ArrayList<StructurePoolElement> copied = new ArrayList<>(size);
                copied.addAll(actual);
                assertSameOrder(expected, copied, "addAll: size=" + size + ", seed=" + seed);
                assertEquals(expectedRandom.nextLong(), actualRandom.nextLong(),
                        "random state drift: size=" + size + ", seed=" + seed);
            }
        }
    }

    @Test
    void printsShuffleHotPathMetrics() {
        int warmup = Integer.getInteger("ga.test.structureShuffleWarmup", 20_000);
        int iterations = Integer.getInteger("ga.test.structureShuffleIterations", 100_000);

        System.out.println("StructurePlacementShuffler hot path benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations);

        runVanillaRotations(0, warmup);
        runFastRotations(0, warmup);
        long vanillaRotationNanos = timeVanillaRotations(10_000L, iterations);
        long fastRotationNanos = timeFastRotations(10_000L, iterations);
        printMetric("rotations", 4, vanillaRotationNanos, fastRotationNanos, iterations);

        for (int size : new int[]{8, 32}) {
            StructurePoolElement[] elements = elements(size);
            ObjectArrayList<StructurePoolElement> source = ObjectArrayList.wrap(elements.clone());
            ArrayList<StructurePoolElement> target = new ArrayList<>(size);

            runVanillaTemplates(source, target, 0, warmup);
            runFastTemplates(source, elements, target, 0, warmup);
            long vanillaTemplateNanos = timeVanillaTemplates(source, target, 20_000L, iterations);
            long fastTemplateNanos = timeFastTemplates(source, elements, target, 20_000L, iterations);
            printMetric("templates", size, vanillaTemplateNanos, fastTemplateNanos, iterations);
        }
    }

    private static long timeVanillaRotations(long seedBase, int iterations) {
        long started = System.nanoTime();
        runVanillaRotations(seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanillaRotations(long seedBase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += consumeRotations(Rotation.getShuffled(RandomSource.create(seedBase + i)));
        }
        sink = local;
    }

    private static long timeFastRotations(long seedBase, int iterations) {
        long started = System.nanoTime();
        runFastRotations(seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runFastRotations(long seedBase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += consumeRotations(StructurePlacementShuffler.shuffledRotations(RandomSource.create(seedBase + i)));
        }
        sink = local;
    }

    private static long timeVanillaTemplates(
            ObjectArrayList<StructurePoolElement> source,
            ArrayList<StructurePoolElement> target,
            long seedBase,
            int iterations
    ) {
        long started = System.nanoTime();
        runVanillaTemplates(source, target, seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanillaTemplates(
            ObjectArrayList<StructurePoolElement> source,
            ArrayList<StructurePoolElement> target,
            long seedBase,
            int iterations
    ) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            target.clear();
            target.addAll(Util.shuffledCopy(source, RandomSource.create(seedBase + i)));
            local += consumeTemplates(target);
        }
        sink = local;
    }

    private static long timeFastTemplates(
            ObjectArrayList<StructurePoolElement> source,
            StructurePoolElement[] elements,
            ArrayList<StructurePoolElement> target,
            long seedBase,
            int iterations
    ) {
        long started = System.nanoTime();
        runFastTemplates(source, elements, target, seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runFastTemplates(
            ObjectArrayList<StructurePoolElement> source,
            StructurePoolElement[] elements,
            ArrayList<StructurePoolElement> target,
            long seedBase,
            int iterations
    ) {
        int local = sink;
        boolean deferred = StructurePlacementShuffler.shouldUseDeferredTemplateShuffle(elements.length);
        for (int i = 0; i < iterations; i++) {
            target.clear();
            if (deferred) {
                target.addAll(StructurePlacementShuffler.shuffledTemplates(elements, RandomSource.create(seedBase + i)));
            } else {
                target.addAll(Util.shuffledCopy(source, RandomSource.create(seedBase + i)));
            }
            local += consumeTemplates(target);
        }
        sink = local;
    }

    private static void printMetric(String label, int size, long vanillaNanos, long fastNanos, int iterations) {
        double vanillaNsOp = (double) vanillaNanos / iterations;
        double fastNsOp = (double) fastNanos / iterations;
        double speedup = vanillaNsOp / Math.max(1.0D, fastNsOp);
        System.out.printf(
                "%s size=%d vanilla=%.1f ns/op fast=%.1f ns/op speedup=%.2fx%n",
                label,
                size,
                vanillaNsOp,
                fastNsOp,
                speedup
        );
    }

    private static StructurePoolElement[] elements(int size) {
        StructurePoolElement[] elements = new StructurePoolElement[size];
        for (int i = 0; i < size; i++) {
            elements[i] = mock(StructurePoolElement.class, withSettings().name("pool-element-" + i));
        }
        return elements;
    }

    private static void assertSameOrder(List<StructurePoolElement> expected, List<StructurePoolElement> actual, String message) {
        assertEquals(expected.size(), actual.size(), message);
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i), message + ", index=" + i);
        }
    }

    private static int consumeRotations(List<Rotation> rotations) {
        int sum = rotations.size();
        for (Rotation rotation : rotations) {
            sum = sum * 31 + rotation.ordinal();
        }
        return sum;
    }

    private static int consumeTemplates(List<StructurePoolElement> elements) {
        int sum = elements.size();
        if (!elements.isEmpty()) {
            sum += System.identityHashCode(elements.get(0));
            sum ^= System.identityHashCode(elements.get(elements.size() - 1));
        }
        return sum;
    }
}
