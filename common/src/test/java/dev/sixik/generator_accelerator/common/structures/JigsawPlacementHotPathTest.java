package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JigsawPlacementHotPathTest {
    private static final ResourceKey<StructureTemplatePool> POOL_A = key("pool_a");
    private static final ResourceKey<StructureTemplatePool> POOL_B = key("pool_b");
    private static final ResourceKey<StructureTemplatePool> POOL_C = key("pool_c");

    private static volatile int sink;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void optimizedProjectionExpansionMatchesVanilla() {
        TestCase testCase = createCase(48);
        int expected = vanillaProjectionExpansion(
                testCase.jigsaws,
                testCase.localBounds,
                testCase.aliasLookup,
                testCase.pools,
                testCase.structureTemplateManager
        );
        int actual = JigsawPlacementHotPath.computeProjectionExpansion(
                testCase.jigsaws,
                testCase.localBounds,
                testCase.aliasLookup,
                testCase.pools,
                testCase.structureTemplateManager,
                testCase.poolMaxSizeCache
        );
        assertEquals(expected, actual);
    }

    @Test
    void optimizedMovedBoxMatchesVanilla() {
        BoundingBox local = new BoundingBox(-3, 4, -5, 6, 12, 8);
        BoundingBox expected = vanillaMoveAndExpand(local, 11, -7, 3, 9);
        BoundingBox actual = JigsawPlacementHotPath.moveAndExpand(local, 11, -7, 3, 9);
        assertEquals(expected, actual);
    }

    @Test
    void optimizedOuterFreeSpaceTrackerMatchesVoxelShapePath() {
        BoundingBox root = new BoundingBox(-48, 0, -48, 48, 64, 48);
        BoundingBox initialOccupied = new BoundingBox(-4, 0, -4, 4, 18, 4);
        CollisionCase testCase = createCollisionCase(root, initialOccupied, 80);

        VoxelShape vanillaFree = Shapes.join(
                Shapes.create(AABB.of(root)),
                Shapes.create(AABB.of(initialOccupied)),
                BooleanOp.ONLY_FIRST
        );
        JigsawFreeSpaceTracker tracker = new JigsawFreeSpaceTracker(root, initialOccupied);

        assertCollisionParity(testCase, vanillaFree, tracker);
    }

    @Test
    void optimizedInnerFreeSpaceTrackerMatchesVoxelShapePath() {
        BoundingBox parentBox = new BoundingBox(-12, 10, -12, 12, 28, 12);
        CollisionCase testCase = createCollisionCase(parentBox, null, 48);

        VoxelShape vanillaFree = Shapes.create(AABB.of(parentBox));
        JigsawFreeSpaceTracker tracker = new JigsawFreeSpaceTracker(parentBox);

        assertCollisionParity(testCase, vanillaFree, tracker);
    }

    @Test
    void optimizedFreeSpaceTrackerMatchesVoxelShapeAtBoundaryEdges() {
        BoundingBox root = new BoundingBox(-16, 0, -16, 16, 16, 16);
        BoundingBox initialOccupied = new BoundingBox(-1, 0, -1, 1, 3, 1);
        CollisionCase testCase = new CollisionCase(root, initialOccupied, List.of(
                new BoundingBox(-16, 0, -16, -14, 2, -14),
                new BoundingBox(-17, 0, 0, -15, 2, 2),
                new BoundingBox(16, 0, 0, 16, 2, 2),
                new BoundingBox(17, 0, 0, 17, 2, 2),
                new BoundingBox(2, 0, -1, 4, 3, 1),
                new BoundingBox(1, 0, -1, 3, 3, 1),
                new BoundingBox(-20, 4, -20, -17, 7, -17),
                new BoundingBox(14, 14, 14, 16, 16, 16)
        ));

        VoxelShape vanillaFree = Shapes.join(
                Shapes.create(AABB.of(root)),
                Shapes.create(AABB.of(initialOccupied)),
                BooleanOp.ONLY_FIRST
        );
        JigsawFreeSpaceTracker tracker = new JigsawFreeSpaceTracker(root, initialOccupied);

        assertCollisionParity(testCase, vanillaFree, tracker);
    }

    @Test
    void optimizedFreeSpaceStateMaterializesShapeForFallbackReaders() {
        BoundingBox root = new BoundingBox(-32, 4, -32, 32, 40, 32);
        BoundingBox initialOccupied = new BoundingBox(-3, 4, -3, 3, 12, 3);
        CollisionCase testCase = createCollisionCase(root, initialOccupied, 64);
        VoxelShape expectedFree = Shapes.join(
                Shapes.create(AABB.of(root)),
                Shapes.create(AABB.of(initialOccupied)),
                BooleanOp.ONLY_FIRST
        );
        JigsawFreeSpaceTracker.State state = JigsawFreeSpaceTracker.ensureOuterState(
                new MutableObject<>(expectedFree),
                initialOccupied
        );

        for (int i = 0; i < testCase.candidates.size(); i++) {
            BoundingBox candidate = testCase.candidates.get(i);
            boolean expected = JigsawFreeSpaceTracker.canPlace(expectedFree, candidate);
            assertEquals(expected, state.canPlace(candidate), "candidate index=" + i + " box=" + candidate);
            if (expected) {
                state.occupy(candidate);
                expectedFree = JigsawFreeSpaceTracker.occupy(expectedFree, candidate);
            }

            VoxelShape materialized = state.getValue();
            for (int probeIndex = i; probeIndex < testCase.candidates.size(); probeIndex += 7) {
                BoundingBox probe = testCase.candidates.get(probeIndex);
                assertEquals(
                        JigsawFreeSpaceTracker.canPlace(expectedFree, probe),
                        JigsawFreeSpaceTracker.canPlace(materialized, probe),
                        "materialized probe index=" + probeIndex + " box=" + probe
                );
            }
        }
    }

    @Test
    void printsJigsawPlacementHotPathMetrics() {
        int warmup = Integer.getInteger("ga.test.jigsawHotPathWarmup", 64);
        int iterations = Integer.getInteger("ga.test.jigsawHotPathIterations", 256);
        TestCase testCase = createCase(96);
        CollisionCase outerCollisionCase = createCollisionCase(
                new BoundingBox(-48, 0, -48, 48, 64, 48),
                new BoundingBox(-4, 0, -4, 4, 18, 4),
                24
        );

        runVanilla(testCase, warmup);
        runOptimized(testCase, warmup);
        runVanillaCollision(outerCollisionCase, warmup);
        runOptimizedCollision(outerCollisionCase, warmup);
        long vanillaNanos = timeVanilla(testCase, iterations);
        long optimizedNanos = timeOptimized(testCase, iterations);
        long vanillaCollisionNanos = timeVanillaCollision(outerCollisionCase, iterations);
        long optimizedCollisionNanos = timeOptimizedCollision(outerCollisionCase, iterations);

        System.out.println("JigsawPlacement expansion benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", connectors=" + testCase.jigsaws.size());
        printMetric("projection-expansion", vanillaNanos, optimizedNanos, iterations);
        printMetric("free-space-collision", vanillaCollisionNanos, optimizedCollisionNanos, iterations * outerCollisionCase.candidates.size());
    }

    private static long timeVanilla(TestCase testCase, int iterations) {
        long started = System.nanoTime();
        runVanilla(testCase, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanilla(TestCase testCase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += vanillaProjectionExpansion(
                    testCase.jigsaws,
                    testCase.localBounds,
                    testCase.aliasLookup,
                    testCase.pools,
                    testCase.structureTemplateManager
            );
        }
        sink = local;
    }

    private static long timeOptimized(TestCase testCase, int iterations) {
        long started = System.nanoTime();
        runOptimized(testCase, iterations);
        return System.nanoTime() - started;
    }

    private static void runOptimized(TestCase testCase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += JigsawPlacementHotPath.computeProjectionExpansion(
                    testCase.jigsaws,
                    testCase.localBounds,
                    testCase.aliasLookup,
                    testCase.pools,
                    testCase.structureTemplateManager,
                    testCase.poolMaxSizeCache
            );
        }
        sink = local;
    }

    private static int vanillaProjectionExpansion(
            List<StructureTemplate.StructureBlockInfo> jigsaws,
            BoundingBox localBounds,
            PoolAliasLookup aliasLookup,
            Registry<StructureTemplatePool> pools,
            StructureTemplateManager structureTemplateManager
    ) {
        return jigsaws.stream().mapToInt(jigsaw -> {
            BlockPos attached = jigsaw.pos().relative(JigsawBlock.getFrontFacing(jigsaw.state()));
            if (!localBounds.isInside(attached)) {
                return 0;
            }
            ResourceKey<StructureTemplatePool> poolKey = aliasLookup.lookup(
                    net.minecraft.data.worldgen.Pools.parseKey(jigsaw.nbt().getString("pool"))
            );
            Optional<Holder.Reference<StructureTemplatePool>> holder = pools.getHolder(poolKey);
            Optional<Holder<StructureTemplatePool>> fallback = holder.map(found -> found.value().getFallback());
            int primary = holder.map(found -> found.value().getMaxSize(structureTemplateManager)).orElse(0);
            int secondary = fallback.map(found -> found.value().getMaxSize(structureTemplateManager)).orElse(0);
            return Math.max(primary, secondary);
        }).max().orElse(0);
    }

    private static BoundingBox vanillaMoveAndExpand(BoundingBox local, int dx, int dy, int dz, int projectionExpansion) {
        BoundingBox moved = local.moved(dx, dy, dz);
        if (projectionExpansion > 0) {
            int extraHeight = Math.max(projectionExpansion + 1, moved.maxY() - moved.minY());
            moved.encapsulate(new BlockPos(moved.minX(), moved.minY() + extraHeight, moved.minZ()));
        }
        return moved;
    }

    private static void assertCollisionParity(CollisionCase testCase, VoxelShape vanillaFree, JigsawFreeSpaceTracker tracker) {
        VoxelShape free = vanillaFree;
        for (int i = 0; i < testCase.candidates.size(); i++) {
            BoundingBox candidate = testCase.candidates.get(i);
            boolean expected = !Shapes.joinIsNotEmpty(
                    free,
                    Shapes.create(AABB.of(candidate).deflate(0.25D)),
                    BooleanOp.ONLY_SECOND
            );
            boolean actual = tracker.canPlace(candidate);
            assertEquals(expected, actual, "candidate index=" + i + " box=" + candidate);
            if (expected) {
                tracker.occupy(candidate);
                free = Shapes.joinUnoptimized(free, Shapes.create(AABB.of(candidate)), BooleanOp.ONLY_FIRST);
            }
        }
    }

    private static long timeVanillaCollision(CollisionCase testCase, int iterations) {
        long started = System.nanoTime();
        runVanillaCollision(testCase, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanillaCollision(CollisionCase testCase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            VoxelShape free = testCase.initialOccupied == null
                    ? Shapes.create(AABB.of(testCase.rootBounds))
                    : Shapes.join(
                            Shapes.create(AABB.of(testCase.rootBounds)),
                            Shapes.create(AABB.of(testCase.initialOccupied)),
                            BooleanOp.ONLY_FIRST
                    );
            for (int j = 0; j < testCase.candidates.size(); j++) {
                BoundingBox candidate = testCase.candidates.get(j);
                if (!Shapes.joinIsNotEmpty(free, Shapes.create(AABB.of(candidate).deflate(0.25D)), BooleanOp.ONLY_SECOND)) {
                    free = Shapes.joinUnoptimized(free, Shapes.create(AABB.of(candidate)), BooleanOp.ONLY_FIRST);
                    local += candidate.minX();
                }
            }
        }
        sink = local;
    }

    private static long timeOptimizedCollision(CollisionCase testCase, int iterations) {
        long started = System.nanoTime();
        runOptimizedCollision(testCase, iterations);
        return System.nanoTime() - started;
    }

    private static void runOptimizedCollision(CollisionCase testCase, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            JigsawFreeSpaceTracker tracker = testCase.initialOccupied == null
                    ? new JigsawFreeSpaceTracker(testCase.rootBounds)
                    : new JigsawFreeSpaceTracker(testCase.rootBounds, testCase.initialOccupied);
            for (int j = 0; j < testCase.candidates.size(); j++) {
                BoundingBox candidate = testCase.candidates.get(j);
                if (tracker.canPlace(candidate)) {
                    tracker.occupy(candidate);
                    local += candidate.minX();
                }
            }
        }
        sink = local;
    }

    private static TestCase createCase(int connectorCount) {
        StructureTemplateManager structureTemplateManager = Mockito.mock(StructureTemplateManager.class, Mockito.withSettings().stubOnly());
        @SuppressWarnings("unchecked")
        Registry<StructureTemplatePool> pools = Mockito.mock(Registry.class, Mockito.withSettings().stubOnly());

        StructureTemplatePool fallbackPool = Mockito.mock(StructureTemplatePool.class, Mockito.withSettings().stubOnly());
        StructureTemplatePool poolA = Mockito.mock(StructureTemplatePool.class, Mockito.withSettings().stubOnly());
        StructureTemplatePool poolB = Mockito.mock(StructureTemplatePool.class, Mockito.withSettings().stubOnly());

        Mockito.when(poolA.getFallback()).thenReturn(Holder.direct(fallbackPool));
        Mockito.when(poolB.getFallback()).thenReturn(Holder.direct(fallbackPool));
        Mockito.when(fallbackPool.getFallback()).thenReturn(Holder.direct(fallbackPool));
        Mockito.when(poolA.getMaxSize(structureTemplateManager)).thenReturn(5);
        Mockito.when(poolB.getMaxSize(structureTemplateManager)).thenReturn(9);
        Mockito.when(fallbackPool.getMaxSize(structureTemplateManager)).thenReturn(7);

        @SuppressWarnings("unchecked")
        Holder.Reference<StructureTemplatePool> poolAHolder = Mockito.mock(Holder.Reference.class, Mockito.withSettings().stubOnly());
        @SuppressWarnings("unchecked")
        Holder.Reference<StructureTemplatePool> poolBHolder = Mockito.mock(Holder.Reference.class, Mockito.withSettings().stubOnly());
        Mockito.when(poolAHolder.value()).thenReturn(poolA);
        Mockito.when(poolBHolder.value()).thenReturn(poolB);
        Mockito.when(pools.getHolder(POOL_A)).thenReturn(Optional.of(poolAHolder));
        Mockito.when(pools.getHolder(POOL_B)).thenReturn(Optional.of(poolBHolder));
        Mockito.when(pools.getHolder(POOL_C)).thenReturn(Optional.empty());

        List<StructureTemplate.StructureBlockInfo> jigsaws = new ArrayList<>(connectorCount);
        BoundingBox localBounds = new BoundingBox(0, 0, 0, 6, 6, 6);
        for (int i = 0; i < connectorCount; i++) {
            boolean inside = (i & 1) == 0;
            ResourceKey<StructureTemplatePool> pool = switch (i % 3) {
                case 0 -> POOL_A;
                case 1 -> POOL_B;
                default -> POOL_C;
            };
            jigsaws.add(new StructureTemplate.StructureBlockInfo(
                    inside ? new BlockPos(5, i & 3, 2) : new BlockPos(6, i & 3, 6),
                    jigsawState(),
                    poolTag(pool)
            ));
        }

        Object2IntMap<ResourceKey<StructureTemplatePool>> cache = new Object2IntOpenHashMap<>();
        cache.defaultReturnValue(-1);
        return new TestCase(jigsaws, localBounds, PoolAliasLookup.EMPTY, pools, structureTemplateManager, cache);
    }

    private static CollisionCase createCollisionCase(BoundingBox rootBounds, BoundingBox initialOccupied, int candidateCount) {
        List<BoundingBox> candidates = new ArrayList<>(candidateCount);
        int spanX = Math.max(1, rootBounds.getXSpan() - 12);
        int spanY = Math.max(1, rootBounds.getYSpan() - 10);
        int spanZ = Math.max(1, rootBounds.getZSpan() - 12);
        for (int i = 0; i < candidateCount; i++) {
            int minX = rootBounds.minX() + Math.floorMod(i * 7, spanX);
            int minY = rootBounds.minY() + Math.floorMod(i * 5, spanY);
            int minZ = rootBounds.minZ() + Math.floorMod(i * 11, spanZ);
            int sizeX = 3 + (i % 4);
            int sizeY = 2 + (i % 5);
            int sizeZ = 3 + (i % 3);
            if ((i & 3) == 0) {
                minX -= 6;
            }
            if ((i & 7) == 3) {
                minZ -= 5;
            }
            candidates.add(new BoundingBox(
                    minX,
                    minY,
                    minZ,
                    minX + sizeX,
                    minY + sizeY,
                    minZ + sizeZ
            ));
        }
        return new CollisionCase(rootBounds, initialOccupied, candidates);
    }

    private static BlockState jigsawState() {
        return Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION, FrontAndTop.EAST_UP);
    }

    private static CompoundTag poolTag(ResourceKey<StructureTemplatePool> poolKey) {
        CompoundTag tag = new CompoundTag();
        tag.putString("pool", poolKey.location().toString());
        return tag;
    }

    private static ResourceKey<StructureTemplatePool> key(String path) {
        return ResourceKey.create(
                net.minecraft.core.registries.Registries.TEMPLATE_POOL,
                ResourceLocation.fromNamespaceAndPath("generator_accelerator", path)
        );
    }

    private static void printMetric(String label, long previousNanos, long nextNanos, int iterations) {
        double previousNsOp = (double) previousNanos / iterations;
        double nextNsOp = (double) nextNanos / iterations;
        double speedup = previousNsOp / Math.max(1.0D, nextNsOp);
        System.out.printf("%s vanilla=%.1f ns/op optimized=%.1f ns/op speedup=%.2fx%n",
                label, previousNsOp, nextNsOp, speedup);
    }

    private record TestCase(
            List<StructureTemplate.StructureBlockInfo> jigsaws,
            BoundingBox localBounds,
            PoolAliasLookup aliasLookup,
            Registry<StructureTemplatePool> pools,
            StructureTemplateManager structureTemplateManager,
            Object2IntMap<ResourceKey<StructureTemplatePool>> poolMaxSizeCache
    ) {
    }

    private record CollisionCase(
            BoundingBox rootBounds,
            BoundingBox initialOccupied,
            List<BoundingBox> candidates
    ) {
    }
}
