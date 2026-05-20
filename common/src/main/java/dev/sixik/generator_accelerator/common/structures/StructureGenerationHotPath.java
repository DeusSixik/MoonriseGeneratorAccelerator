package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class StructureGenerationHotPath {

    private static final ThreadLocal<WorldgenRandom> SHARED_RANDOM =
            ThreadLocal.withInitial(() -> new WorldgenRandom(new LegacyRandomSource(0L)));

    private static final int MAX_VALID_BIOME_PREDICATE_CACHE_SIZE = 4096;

    private static final Map<Structure, Predicate<Holder<Biome>>> VALID_BIOME_PREDICATES = new ConcurrentHashMap<>();

    private StructureGenerationHotPath() {
    }

    public static Structure.GenerationContext createContext(
            Structure structure,
            RegistryAccess registryAccess,
            ChunkGenerator chunkGenerator,
            BiomeSource biomeSource,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            long seed,
            ChunkPos chunkPos,
            LevelHeightAccessor heightAccessor
    ) {
        return createContext(
                registryAccess,
                chunkGenerator,
                biomeSource,
                randomState,
                structureTemplateManager,
                seed,
                chunkPos,
                heightAccessor,
                validBiomePredicate(structure)
        );
    }

    public static Structure.GenerationContext createContext(
            RegistryAccess registryAccess,
            ChunkGenerator chunkGenerator,
            BiomeSource biomeSource,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            long seed,
            ChunkPos chunkPos,
            LevelHeightAccessor heightAccessor,
            Predicate<Holder<Biome>> validBiome
    ) {
        WorldgenRandom random = SHARED_RANDOM.get();
        random.setLargeFeatureSeed(seed, chunkPos.x, chunkPos.z);
        return new Structure.GenerationContext(
                registryAccess,
                chunkGenerator,
                biomeSource,
                randomState,
                structureTemplateManager,
                random,
                seed,
                chunkPos,
                heightAccessor,
                validBiome
        );
    }

    public static boolean isValidBiome(Structure.GenerationStub stub, Structure.GenerationContext context) {
        BlockPos position = stub.position();
        return context.validBiome().test(
                context.chunkGenerator().getBiomeSource().getNoiseBiome(
                        QuartPos.fromBlock(position.getX()),
                        QuartPos.fromBlock(position.getY()),
                        QuartPos.fromBlock(position.getZ()),
                        context.randomState().sampler()
                )
        );
    }

    public static int getMeanFirstOccupiedHeight(Structure.GenerationContext context, int x, int z, int width, int depth) {
        ChunkGenerator chunkGenerator = context.chunkGenerator();
        LevelHeightAccessor heightAccessor = context.heightAccessor();
        RandomState randomState = context.randomState();
        int sum =
                chunkGenerator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
                        + chunkGenerator.getFirstOccupiedHeight(x, z + depth, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
                        + chunkGenerator.getFirstOccupiedHeight(x + width, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
                        + chunkGenerator.getFirstOccupiedHeight(x + width, z + depth, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState);
        // Keep Java /4 truncation for negative heights while using a shift.
        return (sum + ((sum >> 31) & 3)) >> 2;
    }

    private static Predicate<Holder<Biome>> validBiomePredicate(Structure structure) {
        Predicate<Holder<Biome>> cached = VALID_BIOME_PREDICATES.get(structure);
        if (cached != null) {
            return cached;
        }
        if (VALID_BIOME_PREDICATES.size() > MAX_VALID_BIOME_PREDICATE_CACHE_SIZE) {
            VALID_BIOME_PREDICATES.clear();
        }
        return VALID_BIOME_PREDICATES.computeIfAbsent(structure, key -> {
            var biomes = key.biomes();
            return biomes::contains;
        });
    }
}
