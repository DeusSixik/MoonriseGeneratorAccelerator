package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureJigsawConnectorPlanTest {
    private static final String SELECTION_PRIORITY = "selection_priority";
    private static volatile int sink;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void matchesVanillaShuffleStablePrioritySort() {
        int[] sizes = {0, 1, 2, 3, 8, 17, 64};
        BlockPos[] offsets = {BlockPos.ZERO, new BlockPos(3, -2, 11)};

        for (int size : sizes) {
            for (PriorityPattern pattern : PriorityPattern.values()) {
                List<StructureTemplate.StructureBlockInfo> blocks = blocks(size, pattern);
                StructureJigsawConnectorPlan plan = StructureJigsawConnectorPlan.compile(blocks);

                for (BlockPos offset : offsets) {
                    for (long seed = 0; seed < 256; seed++) {
                        RandomSource expectedRandom = RandomSource.create(seed);
                        RandomSource actualRandom = RandomSource.create(seed);
                        List<StructureTemplate.StructureBlockInfo> expected = vanilla(blocks, offset, expectedRandom);
                        List<StructureTemplate.StructureBlockInfo> actual = plan.shuffled(offset, actualRandom);
                        assertEquals(signature(expected), signature(actual),
                                "size=" + size + ", pattern=" + pattern + ", offset=" + offset + ", seed=" + seed);
                        assertEquals(expectedRandom.nextLong(), actualRandom.nextLong(),
                                "random state drift: size=" + size + ", pattern=" + pattern + ", seed=" + seed);
                    }
                }
            }
        }
    }

    @Test
    void printsHotPathMetricsAgainstPreviousCachedPath() {
        int warmup = Integer.getInteger("ga.test.structureJigsawWarmup", 10_000);
        int iterations = Integer.getInteger("ga.test.structureJigsawIterations", 60_000);
        int[] sizes = {16, 64, 256};

        System.out.println("StructureJigsawConnectorPlan hot path benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", offset=(3,-2,11)");
        for (int size : sizes) {
            for (PriorityPattern pattern : new PriorityPattern[]{PriorityPattern.UNIFORM, PriorityPattern.MIXED}) {
                List<StructureTemplate.StructureBlockInfo> blocks = blocks(size, pattern);
                StructureJigsawConnectorPlan plan = StructureJigsawConnectorPlan.compile(blocks);
                BlockPos offset = new BlockPos(3, -2, 11);

                runPlan(plan, offset, 0, warmup);
                runPreviousCachedPath(blocks, offset, 0, warmup);

                long planNanos = timePlan(plan, offset, 10_000L, iterations);
                long previousNanos = timePreviousCachedPath(blocks, offset, 10_000L, iterations);
                double planNsOp = (double) planNanos / iterations;
                double previousNsOp = (double) previousNanos / iterations;
                double speedup = previousNsOp / Math.max(1.0D, planNsOp);

                System.out.printf(
                        "size=%d pattern=%s previous=%.1f ns/op plan=%.1f ns/op speedup=%.2fx%n",
                        size,
                        pattern.name().toLowerCase(),
                        previousNsOp,
                        planNsOp,
                        speedup
                );
            }
        }
    }

    private static long timePlan(StructureJigsawConnectorPlan plan, BlockPos offset, long seedBase, int iterations) {
        long started = System.nanoTime();
        runPlan(plan, offset, seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runPlan(StructureJigsawConnectorPlan plan, BlockPos offset, long seedBase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            List<StructureTemplate.StructureBlockInfo> result = plan.shuffled(offset, RandomSource.create(seedBase + i));
            local += consume(result);
        }
        sink = local;
    }

    private static long timePreviousCachedPath(
            List<StructureTemplate.StructureBlockInfo> cached,
            BlockPos offset,
            long seedBase,
            int iterations
    ) {
        long started = System.nanoTime();
        runPreviousCachedPath(cached, offset, seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runPreviousCachedPath(
            List<StructureTemplate.StructureBlockInfo> cached,
            BlockPos offset,
            long seedBase,
            int iterations
    ) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            List<StructureTemplate.StructureBlockInfo> result = previousCachedPath(cached, offset, RandomSource.create(seedBase + i));
            local += consume(result);
        }
        sink = local;
    }

    private static List<StructureTemplate.StructureBlockInfo> previousCachedPath(
            List<StructureTemplate.StructureBlockInfo> cached,
            BlockPos offset,
            RandomSource random
    ) {
        ObjectArrayList<StructureTemplate.StructureBlockInfo> result = new ObjectArrayList<>(cached);
        Util.shuffle(result, random);
        stablePrioritySort(result);
        if (offset.getX() == 0 && offset.getY() == 0 && offset.getZ() == 0) {
            return result;
        }

        ObjectArrayList<StructureTemplate.StructureBlockInfo> offsetResult = new ObjectArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            StructureTemplate.StructureBlockInfo info = result.get(i);
            offsetResult.add(new StructureTemplate.StructureBlockInfo(info.pos().offset(offset), info.state(), info.nbt()));
        }
        return offsetResult;
    }

    private static List<StructureTemplate.StructureBlockInfo> vanilla(
            List<StructureTemplate.StructureBlockInfo> base,
            BlockPos offset,
            RandomSource random
    ) {
        ObjectArrayList<StructureTemplate.StructureBlockInfo> result = new ObjectArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            StructureTemplate.StructureBlockInfo info = base.get(i);
            result.add(new StructureTemplate.StructureBlockInfo(info.pos().offset(offset), info.state(), info.nbt()));
        }
        Util.shuffle(result, random);
        stablePrioritySort(result);
        return result;
    }

    private static void stablePrioritySort(List<StructureTemplate.StructureBlockInfo> result) {
        result.sort(Comparator.comparingInt(StructureJigsawConnectorPlanTest::priority).reversed());
    }

    private static List<StructureTemplate.StructureBlockInfo> blocks(int size, PriorityPattern pattern) {
        ObjectArrayList<StructureTemplate.StructureBlockInfo> blocks = new ObjectArrayList<>(size);
        for (int i = 0; i < size; i++) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(SELECTION_PRIORITY, pattern.priority(i));
            blocks.add(new StructureTemplate.StructureBlockInfo(
                    new BlockPos(i & 15, i >> 8, (i >> 4) & 15),
                    Blocks.JIGSAW.defaultBlockState(),
                    tag
            ));
        }
        return blocks;
    }

    private static List<Integer> signature(List<StructureTemplate.StructureBlockInfo> blocks) {
        ObjectArrayList<Integer> signature = new ObjectArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            StructureTemplate.StructureBlockInfo info = blocks.get(i);
            signature.add((info.pos().getX() * 31 + info.pos().getY()) * 31 + info.pos().getZ() * 17 + priority(info));
        }
        return signature;
    }

    private static int consume(List<StructureTemplate.StructureBlockInfo> blocks) {
        int sum = blocks.size();
        if (!blocks.isEmpty()) {
            StructureTemplate.StructureBlockInfo first = blocks.get(0);
            StructureTemplate.StructureBlockInfo last = blocks.get(blocks.size() - 1);
            sum += first.pos().getX() * 31 + first.pos().getZ();
            sum += last.pos().getX() * 17 + last.pos().getZ();
        }
        return sum;
    }

    private static int priority(StructureTemplate.StructureBlockInfo info) {
        CompoundTag tag = info.nbt();
        return tag == null ? 0 : tag.getInt(SELECTION_PRIORITY);
    }

    private enum PriorityPattern {
        UNIFORM {
            @Override
            int priority(int index) {
                return 0;
            }
        },
        MIXED {
            @Override
            int priority(int index) {
                return switch (index & 7) {
                    case 0 -> 10;
                    case 1, 2 -> 4;
                    case 3 -> -2;
                    default -> 0;
                };
            }
        },
        UNIQUE {
            @Override
            int priority(int index) {
                return index;
            }
        };

        abstract int priority(int index);
    }
}
