package dev.sixik.generator_accelerator.common.features.mixin;

import dev.sixik.generator_accelerator.api.utils.FastChunkIter;
import dev.sixik.generator_accelerator.common.features.BiomeDecorationScratch;
import dev.sixik.generator_accelerator.common.features.RegistryNameSupplier;
import dev.sixik.generator_accelerator.common.features.StepFeatureCache;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.FeatureCountTracker;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@Mixin(value = ChunkGenerator.class, priority = 999)
public abstract class MixinChunkGenerator$apply_biome_decoration {

    @Unique
    private static final ThreadLocal<@Nullable Int2ObjectMap<ObjectArrayList<Structure>>> STRUCTURE_CACHE =
            new ThreadLocal<>();

    @Unique
    private static final int GA$DECORATION_STEP_COUNT = GenerationStep.Decoration.values().length;

    @Unique
    private static final ThreadLocal<WorldgenRandom> GA$WORLDGEN_RANDOM =
            ThreadLocal.withInitial(() -> new WorldgenRandom(new XoroshiroRandomSource(0L)));

    @Unique
    private static final ThreadLocal<BiomeDecorationScratch> GA$DECORATION_SCRATCH =
            ThreadLocal.withInitial(BiomeDecorationScratch::new);

    @Unique
    private static final ThreadLocal<RegistryNameSupplier> GA$NAME_SUPPLIER =
            ThreadLocal.withInitial(RegistryNameSupplier::new);

    @Unique
    private volatile StepFeatureCache ga$stepFeatureCache;

    @Shadow
    @Final
    protected BiomeSource biomeSource;

    @Shadow
    @Final
    private Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter;

    @Shadow
    private static BoundingBox getWritableArea(ChunkAccess arg) {
        throw new NotImplementedException();
    }

    @Shadow
    @Final
    private Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep;

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public void applyBiomeDecoration(WorldGenLevel pLevel, ChunkAccess pChunk, StructureManager pStructureManager) {
        ChunkPos chunkpos = pChunk.getPos();
        if (!SharedConstants.debugVoidTerrain(chunkpos)) {
            SectionPos sectionpos = SectionPos.of(chunkpos, pLevel.getMinSection());
            BlockPos blockpos = sectionpos.origin();
            Registry<Structure> registry = pLevel.registryAccess().registryOrThrow(Registries.STRUCTURE);

// GENERATOR ACCELERATOR START
            Int2ObjectMap<ObjectArrayList<Structure>> structureByStepMap = STRUCTURE_CACHE.get();
            if(structureByStepMap == null) {
                structureByStepMap = new Int2ObjectOpenHashMap<>();
                for (Structure structure : registry) {
                    int stepId = structure.step().ordinal();

                    structureByStepMap.computeIfAbsent(stepId, k -> new ObjectArrayList<>()).add(structure);
                }
                STRUCTURE_CACHE.set(structureByStepMap);
            }
// GENERATOR ACCELERATOR END

            StepFeatureCache featureCache = this.ga$getStepFeatureCache();
            BiomeDecorationScratch decorationScratch = GA$DECORATION_SCRATCH.get();
            WorldgenRandom worldgenrandom = GA$WORLDGEN_RANDOM.get();
            long i = worldgenrandom.setDecorationSeed(pLevel.getSeed(), blockpos.getX(), blockpos.getZ());
            ObjectArraySet<Holder<Biome>> set = decorationScratch.biomes;
            set.clear();

// GENERATOR ACCELERATOR START
            // REPLACE ChunkPos.rangeClosed
            ChunkPos center = sectionpos.chunk();
            int minX = center.x - 1;
            int maxX = center.x + 1;
            int minZ = center.z - 1;
            int maxZ = center.z + 1;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    ChunkAccess chunkaccess = pLevel.getChunk(x, z);

                    LevelChunkSection[] sections = chunkaccess.getSections();
                    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                        LevelChunkSection levelchunksection = sections[sectionIndex];
                        if (levelchunksection != null) {
                            levelchunksection.getBiomes().getAll(set::add);
                        }
                    }
                }
            }
// GENERATOR ACCELERATOR END

            set.retainAll(this.biomeSource.possibleBiomes());
            int featureDataSize = featureCache.stepCount;

// GENERATOR ACCELERATOR START
            final BoundingBox writableArea = getWritableArea(pChunk);
// GENERATOR ACCELERATOR END

            ChunkGenerator thisObj = (ChunkGenerator)(Object) this;

            try {
                Registry<PlacedFeature> registry1 = pLevel.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
                int i1 = Math.max(GA$DECORATION_STEP_COUNT, featureDataSize);

                for (int k = 0; k < i1; k++) {
                    int l = 0;
                    if (pStructureManager.shouldGenerateStructures()) {
                        for (Structure structure : structureByStepMap.getOrDefault(k,(ObjectArrayList<Structure>) FastChunkIter.EMPTY_LIST)) {
                            worldgenrandom.setFeatureSeed(i, l, k);
                            RegistryNameSupplier supplier = GA$NAME_SUPPLIER.get().set(registry, structure);

                            try {
                                pLevel.setCurrentlyGenerating(supplier);

                                // GENERATOR ACCELERATOR START
                                final ObjectArrayList<StructureStart> structuresList = (ObjectArrayList<StructureStart>) pStructureManager.startsForStructure(sectionpos, structure);
                                final Object[] primitiveArray = structuresList.elements();
                                for (int structStartIndex = 0; structStartIndex < structuresList.size(); structStartIndex++) {
                                    ((StructureStart)primitiveArray[structStartIndex]).placeInChunk(pLevel, pStructureManager, thisObj, worldgenrandom, writableArea, chunkpos);
                                }
                                // GENERATOR ACCELERATOR END
                            } catch (Exception exception) {
                                CrashReport crashreport1 = CrashReport.forThrowable(exception, "Feature placement");
                                crashreport1.addCategory("Feature").setDetail("Description", supplier);
                                throw new ReportedException(crashreport1);
                            }

                            l++;
                        }
                    }

                    if (k < featureDataSize) {
                        // GENERATOR ACCELERATOR START
                        Object[] placedFeatures = featureCache.featuresByStep[k];
                        decorationScratch.beginStep(placedFeatures.length);
                        for (Holder<Biome> holder : set) {
                            int[] indices = featureCache.indicesFor(holder, this.generationSettingsGetter, k);
                            for (int idx = 0; idx < indices.length; idx++) {
                                decorationScratch.addFeatureIndex(indices[idx]);
                            }
                        }
                        // GENERATOR ACCELERATOR END

                        int j1 = decorationScratch.featureIndexCount();
                        int[] aint = decorationScratch.sortedFeatureIndices();

                        for (int k1 = 0; k1 < j1; k1++) {
                            int l1 = aint[k1];
                            PlacedFeature placedfeature = (PlacedFeature) placedFeatures[l1];
                            RegistryNameSupplier supplier1 = GA$NAME_SUPPLIER.get().set(registry1, placedfeature);
                            worldgenrandom.setFeatureSeed(i, l1, k);

                            try {
                                pLevel.setCurrentlyGenerating(supplier1);
                                placedfeature.placeWithBiomeCheck(pLevel, thisObj, worldgenrandom, blockpos);
                            } catch (Exception exception1) {
                                CrashReport crashreport2 = CrashReport.forThrowable(exception1, "Feature placement");
                                crashreport2.addCategory("Feature").setDetail("Description", supplier1);
                                throw new ReportedException(crashreport2);
                            }
                        }
                    }
                }

                pLevel.setCurrentlyGenerating(null);
                GA$NAME_SUPPLIER.get().clear();
                if (SharedConstants.DEBUG_FEATURE_COUNT) {
                    FeatureCountTracker.chunkDecorated(pLevel.getLevel());
                }
            } catch (Exception exception2) {
                CrashReport crashreport = CrashReport.forThrowable(exception2, "Biome decoration");
                crashreport.addCategory("Generation")
                        .setDetail("CenterX", chunkpos.x)
                        .setDetail("CenterZ", chunkpos.z)
                        .setDetail("Decoration Seed", i);
                throw new ReportedException(crashreport);
            }
        }
    }

    @Unique
    private StepFeatureCache ga$getStepFeatureCache() {
        StepFeatureCache cache = this.ga$stepFeatureCache;
        if (cache == null) {
            cache = new StepFeatureCache(this.featuresPerStep.get());
            this.ga$stepFeatureCache = cache;
        }
        return cache;
    }
}
