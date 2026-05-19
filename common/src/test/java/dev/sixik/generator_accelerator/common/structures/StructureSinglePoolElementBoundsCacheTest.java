package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureSinglePoolElementBoundsCacheTest {
    private static volatile int sink;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void cachedLocalBoundingBoxMatchesVanillaMovedBox() throws Exception {
        StructureTemplate template = templateWithSize(new Vec3i(11, 7, 19));
        BlockPos[] positions = {
                BlockPos.ZERO,
                new BlockPos(3, -2, 11),
                new BlockPos(-17, 80, 29)
        };

        for (Rotation rotation : Rotation.values()) {
            BoundingBox local = vanillaBox(template, BlockPos.ZERO, rotation);
            for (BlockPos pos : positions) {
                BoundingBox expected = vanillaBox(template, pos, rotation);
                BoundingBox actual = copyMoved(local, pos);
                assertEquals(expected, actual, "rotation=" + rotation + ", pos=" + pos);
            }
        }
    }

    @Test
    void printsSinglePoolElementBoundsMetrics() throws Exception {
        int warmup = Integer.getInteger("ga.test.structureBoundsWarmup", 40_000);
        int iterations = Integer.getInteger("ga.test.structureBoundsIterations", 200_000);
        StructureTemplate template = templateWithSize(new Vec3i(11, 7, 19));
        BoundingBox[] localBoxes = new BoundingBox[Rotation.values().length];
        for (Rotation rotation : Rotation.values()) {
            localBoxes[rotation.ordinal()] = vanillaBox(template, BlockPos.ZERO, rotation);
        }

        runVanillaBoxes(template, 0, warmup);
        runCachedBoxes(localBoxes, 0, warmup);
        long vanillaNanos = timeVanillaBoxes(template, 10_000, iterations);
        long cachedNanos = timeCachedBoxes(localBoxes, 10_000, iterations);
        double vanillaNsOp = (double) vanillaNanos / iterations;
        double cachedNsOp = (double) cachedNanos / iterations;
        double speedup = vanillaNsOp / Math.max(1.0D, cachedNsOp);

        System.out.println("SinglePoolElement bounds cache hot path benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", size=(11,7,19)");
        System.out.printf("bounds vanilla=%.1f ns/op cached=%.1f ns/op speedup=%.2fx%n",
                vanillaNsOp, cachedNsOp, speedup);
    }

    private static long timeVanillaBoxes(StructureTemplate template, int seedBase, int iterations) {
        long started = System.nanoTime();
        runVanillaBoxes(template, seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanillaBoxes(StructureTemplate template, int seedBase, int iterations) {
        int local = sink;
        Rotation[] rotations = Rotation.values();
        for (int i = 0; i < iterations; i++) {
            Rotation rotation = rotations[i & 3];
            local += consume(vanillaBox(template, position(seedBase + i), rotation));
        }
        sink = local;
    }

    private static long timeCachedBoxes(BoundingBox[] localBoxes, int seedBase, int iterations) {
        long started = System.nanoTime();
        runCachedBoxes(localBoxes, seedBase, iterations);
        return System.nanoTime() - started;
    }

    private static void runCachedBoxes(BoundingBox[] localBoxes, int seedBase, int iterations) {
        int local = sink;
        Rotation[] rotations = Rotation.values();
        for (int i = 0; i < iterations; i++) {
            Rotation rotation = rotations[i & 3];
            local += consume(copyMoved(localBoxes[rotation.ordinal()], position(seedBase + i)));
        }
        sink = local;
    }

    private static BoundingBox vanillaBox(StructureTemplate template, BlockPos pos, Rotation rotation) {
        return template.getBoundingBox(new StructurePlaceSettings().setRotation(rotation), pos);
    }

    private static BoundingBox copyMoved(BoundingBox box, BlockPos pos) {
        int dx = pos.getX();
        int dy = pos.getY();
        int dz = pos.getZ();
        return new BoundingBox(
                box.minX() + dx,
                box.minY() + dy,
                box.minZ() + dz,
                box.maxX() + dx,
                box.maxY() + dy,
                box.maxZ() + dz
        );
    }

    private static BlockPos position(int value) {
        return new BlockPos(value & 31, (value >>> 5) & 15, (value >>> 9) & 31);
    }

    private static int consume(BoundingBox box) {
        return box.minX() * 31 + box.minY() * 17 + box.minZ() + box.maxX() * 13 + box.maxZ();
    }

    private static StructureTemplate templateWithSize(Vec3i size) throws Exception {
        StructureTemplate template = new StructureTemplate();
        Field sizeField = StructureTemplate.class.getDeclaredField("size");
        sizeField.setAccessible(true);
        sizeField.set(template, size);
        return template;
    }
}
