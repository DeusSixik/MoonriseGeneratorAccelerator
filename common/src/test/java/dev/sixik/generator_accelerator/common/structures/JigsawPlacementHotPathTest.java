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
    void printsJigsawPlacementHotPathMetrics() {
        int warmup = Integer.getInteger("ga.test.jigsawHotPathWarmup", 10_000);
        int iterations = Integer.getInteger("ga.test.jigsawHotPathIterations", 60_000);
        TestCase testCase = createCase(96);

        runVanilla(testCase, warmup);
        runOptimized(testCase, warmup);
        long vanillaNanos = timeVanilla(testCase, iterations);
        long optimizedNanos = timeOptimized(testCase, iterations);

        System.out.println("JigsawPlacement expansion benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", connectors=" + testCase.jigsaws.size());
        printMetric("projection-expansion", vanillaNanos, optimizedNanos, iterations);
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
}
