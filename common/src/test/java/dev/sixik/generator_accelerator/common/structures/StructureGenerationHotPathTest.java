package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.server.level.WorldGenRegion;
import com.mojang.serialization.MapCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureGenerationHotPathTest {
    private static volatile int sink;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void optimizedGenerationContextMatchesVanillaValidity() {
        TestContext context = createContext();
        ExposedDummyStructure structure = new ExposedDummyStructure(context.biomeHolder);
        ChunkPos[] chunks = {
                new ChunkPos(0, 0),
                new ChunkPos(3, -7),
                new ChunkPos(41, 19)
        };

        for (ChunkPos chunkPos : chunks) {
            Structure.GenerationContext vanillaContext = vanillaContext(structure, context, chunkPos, 918273645546372819L);
            Structure.GenerationContext optimizedContext = optimizedContext(structure, context, chunkPos, 918273645546372819L);
            assertEquals(
                    structure.findValidGenerationPoint(vanillaContext).isPresent(),
                    structure.optimizedFindValidGenerationPoint(optimizedContext).isPresent(),
                    "chunk=" + chunkPos
            );
        }
    }

    @Test
    void optimizedHeightHelpersMatchVanilla() {
        TestContext context = createContext();
        ExposedDummyStructure structure = new ExposedDummyStructure(context.biomeHolder);
        Structure.GenerationContext generationContext = optimizedContext(structure, context, new ChunkPos(8, -4), 42L);
        int[][] boxes = {
                {12, 30, 4, 4},
                {-17, 9, 6, 3},
                {33, -21, 9, 11}
        };

        for (int[] box : boxes) {
            assertEquals(
                    vanillaMeanFirstOccupiedHeight(generationContext, box[0], box[1], box[2], box[3]),
                    StructureGenerationHotPath.getMeanFirstOccupiedHeight(generationContext, box[0], box[1], box[2], box[3])
            );
        }
    }

    @Test
    void printsStructureGenerationHotPathMetrics() {
        int warmup = Integer.getInteger("ga.test.structureGenerationWarmup", 20_000);
        int iterations = Integer.getInteger("ga.test.structureGenerationIterations", 150_000);
        TestContext context = createContext();
        ExposedDummyStructure structure = new ExposedDummyStructure(context.biomeHolder);
        ChunkPos[] chunks = chunks(64);
        int[][] boxes = boxes(64);

        runVanillaContextSetup(structure, context, chunks, warmup);
        runOptimizedContextSetup(structure, context, chunks, warmup);
        long vanillaContextNanos = timeVanillaContextSetup(structure, context, chunks, iterations);
        long optimizedContextNanos = timeOptimizedContextSetup(structure, context, chunks, iterations);

        Structure.GenerationContext generationContext = optimizedContext(structure, context, new ChunkPos(8, -4), 42L);
        runVanillaMeanHeights(generationContext, boxes, warmup);
        runOptimizedMeanHeights(generationContext, boxes, warmup);
        long vanillaMeanNanos = timeVanillaMeanHeights(generationContext, boxes, iterations);
        long optimizedMeanNanos = timeOptimizedMeanHeights(generationContext, boxes, iterations);

        System.out.println("Structure generation hot path benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", chunks=64, boxes=64");
        printMetric("context-setup", vanillaContextNanos, optimizedContextNanos, iterations);
        printMetric("mean-height", vanillaMeanNanos, optimizedMeanNanos, iterations);
    }

    private static long timeVanillaContextSetup(ExposedDummyStructure structure, TestContext context, ChunkPos[] chunks, int iterations) {
        long started = System.nanoTime();
        runVanillaContextSetup(structure, context, chunks, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanillaContextSetup(ExposedDummyStructure structure, TestContext context, ChunkPos[] chunks, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            Structure.GenerationContext generationContext = vanillaContext(structure, context, chunks[i % chunks.length], 918273645546372819L + i);
            local += generationContext.random().nextInt(32);
        }
        sink = local;
    }

    private static long timeOptimizedContextSetup(ExposedDummyStructure structure, TestContext context, ChunkPos[] chunks, int iterations) {
        long started = System.nanoTime();
        runOptimizedContextSetup(structure, context, chunks, iterations);
        return System.nanoTime() - started;
    }

    private static void runOptimizedContextSetup(ExposedDummyStructure structure, TestContext context, ChunkPos[] chunks, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            Structure.GenerationContext generationContext = optimizedContext(structure, context, chunks[i % chunks.length], 918273645546372819L + i);
            local += generationContext.random().nextInt(32);
        }
        sink = local;
    }

    private static long timeVanillaMeanHeights(Structure.GenerationContext generationContext, int[][] boxes, int iterations) {
        long started = System.nanoTime();
        runVanillaMeanHeights(generationContext, boxes, iterations);
        return System.nanoTime() - started;
    }

    private static void runVanillaMeanHeights(Structure.GenerationContext generationContext, int[][] boxes, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            int[] box = boxes[i % boxes.length];
            local += vanillaMeanFirstOccupiedHeight(generationContext, box[0], box[1], box[2], box[3]);
        }
        sink = local;
    }

    private static long timeOptimizedMeanHeights(Structure.GenerationContext generationContext, int[][] boxes, int iterations) {
        long started = System.nanoTime();
        runOptimizedMeanHeights(generationContext, boxes, iterations);
        return System.nanoTime() - started;
    }

    private static void runOptimizedMeanHeights(Structure.GenerationContext generationContext, int[][] boxes, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            int[] box = boxes[i % boxes.length];
            local += StructureGenerationHotPath.getMeanFirstOccupiedHeight(generationContext, box[0], box[1], box[2], box[3]);
        }
        sink = local;
    }

    private static Structure.GenerationContext vanillaContext(
            ExposedDummyStructure structure,
            TestContext context,
            ChunkPos chunkPos,
            long seed
    ) {
        return new Structure.GenerationContext(
                context.registryAccess,
                context.chunkGenerator,
                context.biomeSource,
                context.randomState,
                context.structureTemplateManager,
                seed,
                chunkPos,
                context.heightAccessor,
                structure.biomes()::contains
        );
    }

    private static Structure.GenerationContext optimizedContext(
            ExposedDummyStructure structure,
            TestContext context,
            ChunkPos chunkPos,
            long seed
    ) {
        return StructureGenerationHotPath.createContext(
                structure,
                context.registryAccess,
                context.chunkGenerator,
                context.biomeSource,
                context.randomState,
                context.structureTemplateManager,
                seed,
                chunkPos,
                context.heightAccessor
        );
    }

    private static int vanillaMeanFirstOccupiedHeight(Structure.GenerationContext context, int x, int z, int width, int depth) {
        int[] heights = vanillaCornerHeights(context, x, z, width, depth);
        return (heights[0] + heights[1] + heights[2] + heights[3]) / 4;
    }

    private static int[] vanillaCornerHeights(Structure.GenerationContext context, int x, int z, int width, int depth) {
        ChunkGenerator chunkGenerator = context.chunkGenerator();
        LevelHeightAccessor heightAccessor = context.heightAccessor();
        RandomState randomState = context.randomState();
        return new int[]{
                chunkGenerator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
                chunkGenerator.getFirstOccupiedHeight(x, z + depth, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
                chunkGenerator.getFirstOccupiedHeight(x + width, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
                chunkGenerator.getFirstOccupiedHeight(x + width, z + depth, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
        };
    }

    private static void printMetric(String label, long previousNanos, long nextNanos, int iterations) {
        double previousNsOp = (double) previousNanos / iterations;
        double nextNsOp = (double) nextNanos / iterations;
        double speedup = previousNsOp / Math.max(1.0D, nextNsOp);
        System.out.printf("%s vanilla=%.1f ns/op optimized=%.1f ns/op speedup=%.2fx%n",
                label, previousNsOp, nextNsOp, speedup);
    }

    private static ChunkPos[] chunks(int count) {
        ChunkPos[] chunks = new ChunkPos[count];
        for (int i = 0; i < count; i++) {
            chunks[i] = new ChunkPos(i * 3 - 71, i * 5 + 19);
        }
        return chunks;
    }

    private static int[][] boxes(int count) {
        int[][] boxes = new int[count][4];
        for (int i = 0; i < count; i++) {
            boxes[i][0] = i * 7 - 83;
            boxes[i][1] = i * 11 - 47;
            boxes[i][2] = 3 + (i & 7);
            boxes[i][3] = 4 + ((i * 3) & 7);
        }
        return boxes;
    }

    private static TestContext createContext() {
        Holder<Biome> biomeHolder = Holder.direct(Mockito.mock(Biome.class, Mockito.withSettings().stubOnly()));
        BiomeSource biomeSource = new FixedBiomeSource(biomeHolder);
        ChunkGenerator chunkGenerator = new TestChunkGenerator(biomeSource);

        RandomState randomState = Mockito.mock(RandomState.class, Mockito.withSettings().stubOnly());
        Mockito.when(randomState.sampler()).thenReturn(null);
        return new TestContext(
                RegistryAccess.EMPTY,
                biomeHolder,
                biomeSource,
                chunkGenerator,
                randomState,
                Mockito.mock(StructureTemplateManager.class, Mockito.withSettings().stubOnly()),
                Mockito.mock(LevelHeightAccessor.class, Mockito.withSettings().stubOnly())
        );
    }

    private record TestContext(
            RegistryAccess registryAccess,
            Holder<Biome> biomeHolder,
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            LevelHeightAccessor heightAccessor
    ) {
    }

    private static final class ExposedDummyStructure extends Structure {

        private ExposedDummyStructure(Holder<Biome> biomeHolder) {
            super(new Structure.StructureSettings(
                    HolderSet.direct(biomeHolder),
                    Map.<MobCategory, StructureSpawnOverride>of(),
                    GenerationStep.Decoration.SURFACE_STRUCTURES,
                    TerrainAdjustment.NONE
            ));
        }

        @Override
        protected Optional<GenerationStub> findGenerationPoint(GenerationContext generationContext) {
            return Optional.of(new GenerationStub(new BlockPos(8, 64, 8), builder -> {
            }));
        }

        private Optional<GenerationStub> optimizedFindValidGenerationPoint(GenerationContext generationContext) {
            Optional<GenerationStub> generationStub = this.findGenerationPoint(generationContext);
            if (generationStub.isEmpty()) {
                return Optional.empty();
            }
            return StructureGenerationHotPath.isValidBiome(generationStub.get(), generationContext) ? generationStub : Optional.empty();
        }

        @Override
        public StructureType<?> type() {
            return null;
        }
    }

    private static final class TestChunkGenerator extends ChunkGenerator {

        private TestChunkGenerator(BiomeSource biomeSource) {
            super(biomeSource, biome -> BiomeGenerationSettings.EMPTY);
        }

        @Override
        protected MapCodec<? extends ChunkGenerator> codec() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyCarvers(WorldGenRegion worldGenRegion, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunkAccess, GenerationStep.Carving carving) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void buildSurface(WorldGenRegion worldGenRegion, StructureManager structureManager, RandomState randomState, ChunkAccess chunkAccess) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getGenDepth() {
            return 384;
        }

        @Override
        public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunkAccess) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getSeaLevel() {
            return 63;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getBaseHeight(int x, int z, Heightmap.Types heightmapType, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
            return sampleHeight(x, z);
        }

        @Override
        public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addDebugScreenInfo(List<String> list, RandomState randomState, BlockPos blockPos) {
        }

        @Override
        public int getFirstOccupiedHeight(int x, int z, Heightmap.Types heightmapType, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
            return sampleHeight(x, z);
        }

        private static int sampleHeight(int x, int z) {
            return 72 + Math.floorMod(x * 13 + z * 7, 41);
        }
    }
}
