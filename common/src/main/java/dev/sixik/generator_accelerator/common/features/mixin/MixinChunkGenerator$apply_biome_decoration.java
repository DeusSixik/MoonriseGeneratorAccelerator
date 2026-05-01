package dev.sixik.generator_accelerator.common.features.mixin;

import com.google.common.base.Suppliers;
import dev.sixik.generator_accelerator.api.utils.FastChunkIter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
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
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.FeatureCountTracker;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

@Mixin(value = ChunkGenerator.class, priority = 4000)
public abstract class MixinChunkGenerator$apply_biome_decoration {

    @Unique
    private static final ThreadLocal<@Nullable Int2ObjectMap<ObjectArrayList<Structure>>> STRUCTURE_CACHE =
            new ThreadLocal<>();

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
//
//    @Unique
//    private Supplier<List<FeatureSorter.StepFeatureData>> bts$customFeaturesPerStep;
//
//    @Inject(method = "<init>(Lnet/minecraft/world/level/biome/BiomeSource;Ljava/util/function/Function;)V", at = @At("RETURN"))
//    private void bts$overrideFeaturesPerStep(BiomeSource biomeSource, Function function, CallbackInfo ci) {
//        bts$customFeaturesPerStep = Suppliers.memoize(() -> FeatureSorter.buildFeaturesPerStep(
//                new ObjectArrayList<>(biomeSource.possibleBiomes()),
//                (holder) -> this.generationSettingsGetter.apply(holder).features(), true)
//        );
//    }


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

            ObjectArrayList<FeatureSorter.StepFeatureData> featureDataList = new ObjectArrayList<>(this.featuresPerStep.get());
            WorldgenRandom worldgenrandom = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
            long i = worldgenrandom.setDecorationSeed(pLevel.getSeed(), blockpos.getX(), blockpos.getZ());
            Set<Holder<Biome>> set = new ObjectArraySet<>();

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
            int featureDataSize = featureDataList.size();

// GENERATOR ACCELERATOR START
            Object[] featureDataArray = featureDataList.elements();
            final BoundingBox writableArea = getWritableArea(pChunk);
// GENERATOR ACCELERATOR END

            ChunkGenerator thisObj = (ChunkGenerator)(Object) this;

            try {
                Registry<PlacedFeature> registry1 = pLevel.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
                int i1 = Math.max(GenerationStep.Decoration.values().length, featureDataSize);

                for (int k = 0; k < i1; k++) {
                    int l = 0;
                    if (pStructureManager.shouldGenerateStructures()) {
                        for (Structure structure : structureByStepMap.getOrDefault(k,(ObjectArrayList<Structure>) FastChunkIter.EMPTY_LIST)) {
                            worldgenrandom.setFeatureSeed(i, l, k);
                            java.util.function.Supplier<String> supplier = () -> registry.getResourceKey(structure).map(Object::toString).orElseGet(structure::toString);

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
                                crashreport1.addCategory("Feature").setDetail("Description", supplier::get);
                                throw new ReportedException(crashreport1);
                            }

                            l++;
                        }
                    }

                    if (k < featureDataSize) {
                        IntSet intset = new IntArraySet();

                        // GENERATOR ACCELERATOR START
                        final FeatureSorter.StepFeatureData featuresorter$stepfeaturedata =
                                (FeatureSorter.StepFeatureData) featureDataArray[k];

                        ToIntFunction<PlacedFeature> indexMapper = featuresorter$stepfeaturedata.indexMapping();
                        for (Holder<Biome> holder : set) {
                            List<HolderSet<PlacedFeature>> placedFeatureHolderSet = this.generationSettingsGetter.apply(holder).features();

                            if (k < placedFeatureHolderSet.size()) {
                                HolderSet<PlacedFeature> holderset = placedFeatureHolderSet.get(k);
                                int size = holderset.size();
                                for (int holderIndex = 0; holderIndex < size; holderIndex++) {
                                    PlacedFeature feature = holderset.get(holderIndex).value();
                                    intset.add(indexMapper.applyAsInt(feature));
                                }
                            }
                        }
                        // GENERATOR ACCELERATOR END

                        int j1 = intset.size();
                        int[] aint = intset.toIntArray();
                        Arrays.sort(aint);

                        for (int k1 = 0; k1 < j1; k1++) {
                            int l1 = aint[k1];
                            PlacedFeature placedfeature = featuresorter$stepfeaturedata.features().get(l1);
                            java.util.function.Supplier<String> supplier1 = () -> registry1.getResourceKey(placedfeature).map(Object::toString).orElseGet(placedfeature::toString);
                            worldgenrandom.setFeatureSeed(i, l1, k);

                            try {
                                pLevel.setCurrentlyGenerating(supplier1);
                                placedfeature.placeWithBiomeCheck(pLevel, thisObj, worldgenrandom, blockpos);
                            } catch (Exception exception1) {
                                CrashReport crashreport2 = CrashReport.forThrowable(exception1, "Feature placement");
                                crashreport2.addCategory("Feature").setDetail("Description", supplier1::get);
                                throw new ReportedException(crashreport2);
                            }
                        }
                    }
                }

                pLevel.setCurrentlyGenerating(null);
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
}
