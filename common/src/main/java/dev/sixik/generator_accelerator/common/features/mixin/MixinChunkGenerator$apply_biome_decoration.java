package dev.sixik.generator_accelerator.common.features.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$StructureManagerExtension;
import dev.sixik.generator_accelerator.common.features.BiomeDecorationScratch;
import dev.sixik.generator_accelerator.common.features.RegistryNameSupplier;
import dev.sixik.generator_accelerator.common.features.StepFeatureCache;
import dev.sixik.generator_accelerator.common.features.StructureStepCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.BitStorage;
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
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
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
    private static final ThreadLocal<@Nullable StructureStepCache> STRUCTURE_CACHE =
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
            StructureStepCache structureStepCache = STRUCTURE_CACHE.get();
            if (structureStepCache == null || structureStepCache.registry() != registry) {
                structureStepCache = new StructureStepCache(registry, GA$DECORATION_STEP_COUNT);
                STRUCTURE_CACHE.set(structureStepCache);
            }
// GENERATOR ACCELERATOR END

            StepFeatureCache featureCache = this.ga$getStepFeatureCache();
            BiomeDecorationScratch decorationScratch = GA$DECORATION_SCRATCH.get();
            WorldgenRandom worldgenrandom = GA$WORLDGEN_RANDOM.get();
            RegistryNameSupplier nameSupplier = GA$NAME_SUPPLIER.get();
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
                            ga$collectSectionBiomes(levelchunksection, set, decorationScratch);
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
                int i1 = Math.max(Math.max(GA$DECORATION_STEP_COUNT, featureDataSize), structureStepCache.stepCount());
                final boolean generateStructures = pStructureManager.shouldGenerateStructures();
                boolean biomeFeatureMasksReady = false;

                for (int k = 0; k < i1; k++) {
                    int l = 0;
                    if (generateStructures) {
                        ObjectArrayList<Structure> structuresForStep = structureStepCache.structuresAt(k);
                        if (structuresForStep != null) {
                            Object[] structures = structuresForStep.elements();
                            for (int structureIndex = 0, structureCount = structuresForStep.size(); structureIndex < structureCount; structureIndex++) {
                                Structure structure = (Structure) structures[structureIndex];
                                worldgenrandom.setFeatureSeed(i, l, k);
                                RegistryNameSupplier supplier = nameSupplier.set(registry, structure);

                                try {
                                    pLevel.setCurrentlyGenerating(supplier);

                                    // GENERATOR ACCELERATOR START
                                    final ObjectArrayList<StructureStart> structuresList = ((GA$StructureManagerExtension) pStructureManager).ga$startsForStructureFast(sectionpos, structure);
                                    if (structuresList != null) {
                                        final Object[] primitiveArray = structuresList.elements();
                                        for (int structStartIndex = 0; structStartIndex < structuresList.size(); structStartIndex++) {
                                            ((StructureStart)primitiveArray[structStartIndex]).placeInChunk(pLevel, pStructureManager, thisObj, worldgenrandom, writableArea, chunkpos);
                                        }
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
                    }

                    if (k < featureDataSize) {
                        // GENERATOR ACCELERATOR START
                        Object[] placedFeatures = featureCache.featuresByStep[k];
                        if (placedFeatures.length == 0 || set.isEmpty()) {
                            continue;
                        }
                        if (!biomeFeatureMasksReady) {
                            decorationScratch.beginBiomeFeatureMasks(set.size());
                            for (Holder<Biome> holder : set) {
                                decorationScratch.addBiomeFeatureMasks(featureCache.featureMasksFor(holder, this.generationSettingsGetter));
                            }
                            biomeFeatureMasksReady = true;
                        }
                        decorationScratch.beginStepWords(featureCache.featureMaskWordsByStep[k]);
                        long[][][] biomeFeatureMasks = decorationScratch.biomeFeatureMasks();
                        for (int biomeIndex = 0, biomeCount = decorationScratch.biomeFeatureMaskCount(); biomeIndex < biomeCount; biomeIndex++) {
                            long[] mask = biomeFeatureMasks[biomeIndex][k];
                            if (mask != null) {
                                decorationScratch.addFeatureMask(mask);
                            }
                        }
                        // GENERATOR ACCELERATOR END

                        int[] aint = decorationScratch.collectFeatureIndices();
                        int j1 = decorationScratch.featureIndexCount();

                        for (int k1 = 0; k1 < j1; k1++) {
                            int l1 = aint[k1];
                            PlacedFeature placedfeature = (PlacedFeature) placedFeatures[l1];
                            RegistryNameSupplier supplier1 = nameSupplier.set(registry1, placedfeature);
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
                nameSupplier.clear();
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
            } finally {
                decorationScratch.clearBiomeFeatureMasks();
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

    @Unique
    @SuppressWarnings("unchecked")
    private static void ga$collectSectionBiomes(LevelChunkSection section, ObjectArraySet<Holder<Biome>> biomesOut, BiomeDecorationScratch scratch) {
        PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
        if (!(biomes instanceof PalettedContainer<?> rawContainer)) {
            biomes.getAll(biomesOut::add);
            return;
        }
        if (rawContainer.getClass() != PalettedContainer.class) {
            biomes.getAll(biomesOut::add);
            return;
        }

        PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) rawContainer;
        PalettedContainer.Data<Holder<Biome>> data = container.data;
        Palette<Holder<Biome>> palette = data.palette();
        int paletteSize = palette.getSize();
        if (paletteSize == 1) {
            biomesOut.add(palette.valueFor(0));
            return;
        }

        BitStorage storage = data.storage();
        int[] uniquePaletteIndices = scratch.biomePaletteIndices();
        int storageSize = storage.getSize();
        if (storageSize > uniquePaletteIndices.length) {
            biomes.getAll(biomesOut::add);
            return;
        }
        int uniqueCount = 0;
        int targetUniqueCount = Math.min(paletteSize, storageSize);
        for (int i = 0; i < storageSize; i++) {
            int paletteIndex = storage.get(i);
            boolean seen = false;
            for (int j = 0; j < uniqueCount; j++) {
                if (uniquePaletteIndices[j] == paletteIndex) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                uniquePaletteIndices[uniqueCount++] = paletteIndex;
                biomesOut.add(palette.valueFor(paletteIndex));
                if (uniqueCount == targetUniqueCount) {
                    return;
                }
            }
        }
    }

}
