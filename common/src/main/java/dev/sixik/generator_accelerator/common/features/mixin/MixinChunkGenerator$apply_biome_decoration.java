package dev.sixik.generator_accelerator.common.features.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$StructureManagerExtension;
import dev.sixik.generator_accelerator.common.features.BiomeDecorationScratch;
import dev.sixik.generator_accelerator.common.features.BiomeSignatureFeatureMaskCache;
import dev.sixik.generator_accelerator.common.features.FeatureMemoryDebug;
import dev.sixik.generator_accelerator.common.features.FeatureCacheEpoch;
import dev.sixik.generator_accelerator.common.features.RegistryNameSupplier;
import dev.sixik.generator_accelerator.common.features.StepFeatureCache;
import dev.sixik.generator_accelerator.common.features.StructureStepCache;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineExecutor;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineMetrics;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineScratch;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPlan;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationStepPlan;
import dev.sixik.generator_accelerator.common.features.pipeline.JavaDecorationCompiler;
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
import java.util.Set;
import java.lang.ref.WeakReference;
import java.util.function.Function;
import java.util.function.Supplier;

@Mixin(value = ChunkGenerator.class, priority = 999)
public abstract class MixinChunkGenerator$apply_biome_decoration {

    @Unique
    private static final ThreadLocal<WeakReference<StructureStepCache>> STRUCTURE_CACHE =
            ThreadLocal.withInitial(() -> new WeakReference<>(null));

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
    private static final ThreadLocal<DecorationPipelineExecutor> GA$DECORATION_PIPELINE_EXECUTOR =
            ThreadLocal.withInitial(DecorationPipelineExecutor::new);

    @Unique
    private static final ThreadLocal<JavaDecorationCompiler> GA$DECORATION_COMPILER =
            ThreadLocal.withInitial(JavaDecorationCompiler::new);

    @Unique
    private volatile StepFeatureCache ga$stepFeatureCache;

    @Unique
    private volatile DecorationPlan ga$decorationPlan;

    @Unique
    private volatile StepFeatureCache ga$biomeSignatureFeatureCacheOwner;

    @Unique
    private volatile BiomeSignatureFeatureMaskCache ga$biomeSignatureMaskCache;

    @Unique
    private volatile int ga$featureCacheEpoch = Integer.MIN_VALUE;

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
        long decorationStart = DecorationPipelineMetrics.startTimer();
        ChunkPos chunkpos = pChunk.getPos();
        if (!SharedConstants.debugVoidTerrain(chunkpos)) {
            SectionPos sectionpos = SectionPos.of(chunkpos, pLevel.getMinSection());
            BlockPos blockpos = sectionpos.origin();
            Registry<Structure> registry = pLevel.registryAccess().registryOrThrow(Registries.STRUCTURE);

// GENERATOR ACCELERATOR START
            StructureStepCache structureStepCache = STRUCTURE_CACHE.get().get();
            if (structureStepCache == null || structureStepCache.registry() != registry) {
                structureStepCache = new StructureStepCache(registry, GA$DECORATION_STEP_COUNT);
                STRUCTURE_CACHE.set(new WeakReference<>(structureStepCache));
            }
// GENERATOR ACCELERATOR END

            this.ga$ensureFeatureCacheEpoch();

            StepFeatureCache featureCache = this.ga$getStepFeatureCache();
            DecorationPlan decorationPlan = this.ga$getDecorationPlan(featureCache);
            BiomeDecorationScratch decorationScratch = GA$DECORATION_SCRATCH.get();
            DecorationPipelineScratch pipelineScratch = DecorationPipelineScratch.local();
            WorldgenRandom worldgenrandom = GA$WORLDGEN_RANDOM.get();
            RegistryNameSupplier nameSupplier = GA$NAME_SUPPLIER.get();
            long i = worldgenrandom.setDecorationSeed(pLevel.getSeed(), blockpos.getX(), blockpos.getZ());
            ObjectArraySet<Holder<Biome>> set = decorationScratch.biomes;
            set.clear();
            Set<Holder<Biome>> possibleBiomes = this.biomeSource.possibleBiomes();
            int featureDataSize = featureCache.stepCount;
            if (featureDataSize > 0) {
                decorationScratch.beginCombinedFeatureMasks(featureDataSize, featureCache.featureMaskWordsByStep);
            }

// GENERATOR ACCELERATOR START
            // REPLACE ChunkPos.rangeClosed
            int possibleBiomeCount = possibleBiomes.size();
            if (featureDataSize > 0 && possibleBiomeCount == 1) {
                set.add(possibleBiomes.iterator().next());
            } else if (featureDataSize > 0 && possibleBiomeCount > 0) {
                ChunkPos center = sectionpos.chunk();
                int minX = center.x - 1;
                int maxX = center.x + 1;
                int minZ = center.z - 1;
                int maxZ = center.z + 1;
                long biomeScanStart = DecorationPipelineMetrics.startTimer();
                biomeScan:
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        ChunkAccess chunkaccess = pLevel.getChunk(x, z);

                        LevelChunkSection[] sections = chunkaccess.getSections();
                        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                            LevelChunkSection levelchunksection = sections[sectionIndex];
                            if (levelchunksection != null) {
                                ga$collectSectionBiomes(levelchunksection, set, possibleBiomes, decorationScratch);
                                if (set.size() >= possibleBiomeCount) {
                                    break biomeScan;
                                }
                            }
                        }
                    }
                }
                DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.OUTER_BIOME_SCAN_NANOS, biomeScanStart);
            }
            if (featureDataSize > 0 && !set.isEmpty()) {
                if (set.size() == 1) {
                    long maskCombineStart = DecorationPipelineMetrics.startTimer();
                    decorationScratch.addBiomeFeatureData(
                            featureCache.featureDataFor(this.ga$canonicalBiomeHolder(set.iterator().next(), possibleBiomes), this.generationSettingsGetter),
                            featureCache.featureMaskWordsByStep
                    );
                    DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.OUTER_MASK_COMBINE_NANOS, maskCombineStart);
                } else {
                    BiomeSignatureFeatureMaskCache maskCache = this.ga$getBiomeSignatureMaskCache(featureCache);
                    if (!maskCache.copyIfPresent(set, decorationScratch, featureDataSize, featureCache.featureMaskWordsByStep)) {
                        long maskCombineStart = DecorationPipelineMetrics.startTimer();
                        for (Holder<Biome> biome : set) {
                            decorationScratch.addBiomeFeatureData(
                                    featureCache.featureDataFor(this.ga$canonicalBiomeHolder(biome, possibleBiomes), this.generationSettingsGetter),
                                    featureCache.featureMaskWordsByStep
                            );
                        }
                        DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.OUTER_MASK_COMBINE_NANOS, maskCombineStart);
                        maskCache.store(set, decorationScratch, featureDataSize, featureCache.featureMaskWordsByStep);
                    }
                }
            }
// GENERATOR ACCELERATOR END

// GENERATOR ACCELERATOR START
            final BoundingBox writableArea = getWritableArea(pChunk);
// GENERATOR ACCELERATOR END

            ChunkGenerator thisObj = (ChunkGenerator)(Object) this;

            try {
                Registry<PlacedFeature> registry1 = pLevel.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
                DecorationPipelineExecutor pipelineExecutor = GA$DECORATION_PIPELINE_EXECUTOR.get();
                DecorationPipelineExecutor.ExecutionContext pipelineContext = new DecorationPipelineExecutor.ExecutionContext(
                        pLevel,
                        pChunk,
                        thisObj,
                        worldgenrandom,
                        blockpos,
                        i,
                        registry1,
                        (featureIndex, feature) -> pLevel.setCurrentlyGenerating(nameSupplier.set(registry1, feature)),
                        pipelineScratch.placementContext(pLevel, thisObj)
                );
                int i1 = Math.max(Math.max(GA$DECORATION_STEP_COUNT, featureDataSize), structureStepCache.stepCount());
                final boolean generateStructures = pStructureManager.shouldGenerateStructures();

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
                        if (featureCache.featuresByStep[k].length == 0 || set.isEmpty() || !decorationScratch.stepHasFeatures(k)) {
                            continue;
                        }
                        int wordCount = featureCache.featureMaskWordsByStep[k];
                        if (wordCount == 0) {
                            continue;
                        }
                        // GENERATOR ACCELERATOR END

                        DecorationStepPlan stepPlan = decorationPlan.step(k);
                        if (stepPlan == null) {
                            continue;
                        }

                        try {
                            pipelineExecutor.executeSelectedMask(
                                    stepPlan,
                                    pipelineContext,
                                    decorationScratch.featureMaskForStep(k),
                                    wordCount
                            );
                        } catch (Exception exception1) {
                            CrashReport crashreport2 = CrashReport.forThrowable(exception1, "Feature placement");
                            crashreport2.addCategory("Feature").setDetail("Description", nameSupplier);
                            throw new ReportedException(crashreport2);
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
                set.clear();
                FeatureMemoryDebug.maybeLogDecorationChunk(chunkpos, set.size(), pipelineScratch);
                pipelineScratch.clear();
                DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_TOTAL_NANOS, decorationStart);
            }
        }
    }

    @Unique
    private StepFeatureCache ga$getStepFeatureCache() {
        StepFeatureCache cache = this.ga$stepFeatureCache;
        if (cache == null) {
            synchronized (this) {
                cache = this.ga$stepFeatureCache;
                if (cache == null) {
                    cache = new StepFeatureCache(this.featuresPerStep.get());
                    this.ga$stepFeatureCache = cache;
                }
            }
        }
        return cache;
    }

    @Unique
    private DecorationPlan ga$getDecorationPlan(StepFeatureCache featureCache) {
        DecorationPlan plan = this.ga$decorationPlan;
        if (plan == null || plan.stepCount() != featureCache.stepCount) {
            synchronized (this) {
                plan = this.ga$decorationPlan;
                if (plan == null || plan.stepCount() != featureCache.stepCount) {
                    plan = GA$DECORATION_COMPILER.get().compile(featureCache.featuresByStep);
                    this.ga$decorationPlan = plan;
                }
            }
        }
        return plan;
    }

    @Unique
    private BiomeSignatureFeatureMaskCache ga$getBiomeSignatureMaskCache(StepFeatureCache featureCache) {
        BiomeSignatureFeatureMaskCache cache = this.ga$biomeSignatureMaskCache;
        if (cache == null || this.ga$biomeSignatureFeatureCacheOwner != featureCache) {
            synchronized (this) {
                cache = this.ga$biomeSignatureMaskCache;
                if (cache == null || this.ga$biomeSignatureFeatureCacheOwner != featureCache) {
                    cache = new BiomeSignatureFeatureMaskCache();
                    this.ga$biomeSignatureFeatureCacheOwner = featureCache;
                    this.ga$biomeSignatureMaskCache = cache;
                }
            }
        }
        return cache;
    }

    @Unique
    private void ga$ensureFeatureCacheEpoch() {
        int epoch = FeatureCacheEpoch.current();
        if (this.ga$featureCacheEpoch == epoch) {
            return;
        }

        synchronized (this) {
            if (this.ga$featureCacheEpoch == epoch) {
                return;
            }
            this.ga$stepFeatureCache = null;
            this.ga$decorationPlan = null;
            this.ga$biomeSignatureFeatureCacheOwner = null;
            this.ga$biomeSignatureMaskCache = null;
            this.ga$featureCacheEpoch = epoch;
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void ga$collectSectionBiomes(
            LevelChunkSection section,
            ObjectArraySet<Holder<Biome>> biomesOut,
            Set<Holder<Biome>> possibleBiomes,
            BiomeDecorationScratch scratch
    ) {
        PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
        if (!(biomes instanceof PalettedContainer<?> rawContainer)) {
            biomes.getAll(biome -> {
                this.ga$recordBiome(biome, biomesOut, possibleBiomes);
            });
            return;
        }
        if (rawContainer.getClass() != PalettedContainer.class) {
            biomes.getAll(biome -> {
                this.ga$recordBiome(biome, biomesOut, possibleBiomes);
            });
            return;
        }

        PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) rawContainer;
        PalettedContainer.Data<Holder<Biome>> data = container.data;
        Palette<Holder<Biome>> palette = data.palette();
        int paletteSize = palette.getSize();
        if (paletteSize == 1) {
            this.ga$recordBiome(palette.valueFor(0), biomesOut, possibleBiomes);
            return;
        }

        BitStorage storage = data.storage();
        int[] uniquePaletteIndices = scratch.biomePaletteIndices();
        int storageSize = storage.getSize();
        if (storageSize > uniquePaletteIndices.length) {
            biomes.getAll(biome -> {
                this.ga$recordBiome(biome, biomesOut, possibleBiomes);
            });
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
                this.ga$recordBiome(palette.valueFor(paletteIndex), biomesOut, possibleBiomes);
                if (uniqueCount == targetUniqueCount) {
                    return;
                }
            }
        }
    }

    @Unique
    private void ga$recordBiome(
            Holder<Biome> biome,
            ObjectArraySet<Holder<Biome>> biomesOut,
            Set<Holder<Biome>> possibleBiomes
    ) {
        biomesOut.add(this.ga$canonicalBiomeHolder(biome, possibleBiomes));
    }

    @Unique
    private Holder<Biome> ga$canonicalBiomeHolder(
            Holder<Biome> biome,
            Set<Holder<Biome>> possibleBiomes
    ) {
        if (possibleBiomes.contains(biome)) {
            return biome;
        }

        for (Holder<Biome> possibleBiome : possibleBiomes) {
            if (possibleBiome.equals(biome)) {
                return possibleBiome;
            }
        }
        return biome;
    }

}
