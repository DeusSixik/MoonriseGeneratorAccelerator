package dev.sixik.generator_accelerator.common.structures.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$ChunkGeneratorStructureStateExtern;
import dev.sixik.generator_accelerator.common.structures.StructureStartFastLookup;
import dev.sixik.generator_accelerator.common.structures.StructureStartMetrics;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Map;

@Mixin(value = ChunkGenerator.class, priority = 997)
public abstract class MixinChunkGenerator$optimize_creating_structure {

    @Unique
    private static final ThreadLocal<WorldgenRandom> SHARED_RANDOM =
            ThreadLocal.withInitial(() ->
                    new WorldgenRandom(new LegacyRandomSource(0L)
                    ));

    @Unique
    private static final ThreadLocal<int[]> INDEX_POOL =
            ThreadLocal.withInitial(() -> new int[32]);
    @Unique
    private static final boolean bts$START_SNAPSHOT_CHECK = Boolean.parseBoolean(System.getProperty(
            "ga.structures.createStructures.startSnapshot.enabled",
            "false"
    ));
    @Unique
    private static final boolean bts$FAST_START_LOOKUP = Boolean.parseBoolean(System.getProperty(
            "ga.structures.createStructures.fastStartLookup.enabled",
            "false"
    ));

    @Shadow
    protected abstract boolean tryGenerateStructure(StructureSet.StructureSelectionEntry arg, StructureManager arg2, RegistryAccess arg3, RandomState arg4, StructureTemplateManager arg5, long l, ChunkAccess arg6, ChunkPos arg7, SectionPos arg8);

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public void createStructures(
            RegistryAccess pRegistryAccess,
            ChunkGeneratorStructureState pStructureState,
            StructureManager pStructureManager,
            ChunkAccess pChunk,
            StructureTemplateManager pStructureTemplateManager
    ) {
        if (SharedConstants.DEBUG_DISABLE_STRUCTURES) return;

        ChunkPos chunkpos = pChunk.getPos();
        SectionPos sectionpos = SectionPos.bottomOf(pChunk);
        RandomState randomstate = pStructureState.randomState();
        final Holder<StructureSet>[] possibleStructureSets = GA$ChunkGeneratorStructureStateExtern.get(pStructureState).getPossibleStructureSetsArray();
        StructureStartFastLookup fastStartLookup = bts$FAST_START_LOOKUP && pChunk instanceof StructureStartFastLookup lookup
                ? lookup
                : null;
        Map<Structure, StructureStart> existingStarts = bts$START_SNAPSHOT_CHECK ? pChunk.getAllStarts() : null;

        outer:
        for (int pSSize = 0; pSSize < possibleStructureSets.length; pSSize++) {
            final StructureSet set = possibleStructureSets[pSSize].value();
            StructurePlacement placement = set.placement();
            List<StructureSet.StructureSelectionEntry> structures = set.structures();
            StructureStartMetrics.recordStructureSet();

            if (bts$hasExistingStart(pChunk, structures, fastStartLookup, existingStarts)) {
                continue outer;
            }

            boolean isStructureChunk = bts$isStructureChunkTimed(placement, pStructureState, chunkpos);
            if (isStructureChunk) {
                boolean generated;
                if (structures.size() == 1) {
                    generated = this.bts$tryGenerateStructureTimed(structures.get(0), pStructureManager, pRegistryAccess,
                            randomstate, pStructureTemplateManager, pStructureState.getLevelSeed(),
                            pChunk, chunkpos, sectionpos);
                } else {
                    generated = bts$processWeightedSelection(structures, pStructureManager, pRegistryAccess,
                            randomstate, pStructureTemplateManager, pStructureState.getLevelSeed(),
                            pChunk, chunkpos, sectionpos);
                }
                if (bts$START_SNAPSHOT_CHECK && generated) {
                    existingStarts = pChunk.getAllStarts();
                    StructureStartMetrics.recordSnapshotRefresh();
                }
            }
        }
    }

    @Unique
    private boolean bts$hasExistingStart(
            ChunkAccess pChunk,
            List<StructureSet.StructureSelectionEntry> structures,
            StructureStartFastLookup fastStartLookup,
            Map<Structure, StructureStart> existingStarts
    ) {
        if (fastStartLookup != null) {
            long start = StructureStartMetrics.startTimer();
            boolean hit = fastStartLookup.ga$hasAnyStartFor(structures);
            StructureStartMetrics.recordFastLookup(start, structures.size(), hit);
            return hit;
        }

        long start = StructureStartMetrics.startTimer();
        int checked = 0;
        if (bts$START_SNAPSHOT_CHECK && (existingStarts == null || existingStarts.isEmpty())) {
            StructureStartMetrics.recordDuplicateStartCheck(start, checked, false, true);
            return false;
        }
        for (int structureIndex = 0; structureIndex < structures.size(); structureIndex++) {
            checked++;
            Structure structure = structures.get(structureIndex).structure().value();
            boolean hit = bts$START_SNAPSHOT_CHECK
                    ? existingStarts.containsKey(structure)
                    : pChunk.getStartForStructure(structure) != null;
            if (hit) {
                StructureStartMetrics.recordDuplicateStartCheck(start, checked, true, bts$START_SNAPSHOT_CHECK);
                return true;
            }
        }
        StructureStartMetrics.recordDuplicateStartCheck(start, checked, false, bts$START_SNAPSHOT_CHECK);
        return false;
    }

    @Unique
    private boolean bts$isStructureChunkTimed(
            StructurePlacement placement,
            ChunkGeneratorStructureState pStructureState,
            ChunkPos chunkpos
    ) {
        long start = StructureStartMetrics.startTimer();
        boolean hit = placement.isStructureChunk(pStructureState, chunkpos.x, chunkpos.z);
        StructureStartMetrics.recordPlacementCheck(start, hit);
        return hit;
    }

    @Unique
    private boolean bts$tryGenerateStructureTimed(
            StructureSet.StructureSelectionEntry entry,
            StructureManager pStructureManager,
            RegistryAccess pRegistryAccess,
            RandomState pRandom,
            StructureTemplateManager pStructureTemplateManager,
            long pSeed,
            ChunkAccess pChunk,
            ChunkPos pChunkPos,
            SectionPos pSectionPos
    ) {
        long start = StructureStartMetrics.startTimer();
        Structure structure = null;
        String structureName = null;
        if (StructureStartMetrics.TYPE_METRICS_ENABLED) {
            structure = entry.structure().value();
            structureName = bts$structureMetricName(pRegistryAccess, structure);
        }
        boolean generated = this.tryGenerateStructure(entry, pStructureManager, pRegistryAccess,
                pRandom, pStructureTemplateManager, pSeed, pChunk, pChunkPos, pSectionPos);
        StructureStartMetrics.recordTryGenerate(start, generated, structureName);
        return generated;
    }

    @Unique
    private static String bts$structureMetricName(RegistryAccess registryAccess, Structure structure) {
        if (structure == null) {
            return "unknown";
        }
        try {
            ResourceLocation key = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(structure);
            if (key != null) {
                return key.toString();
            }
        } catch (Throwable ignored) {
            // Fall back to type/class names if the dynamic registry is unavailable.
        }
        try {
            ResourceLocation typeKey = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());
            if (typeKey != null) {
                return "type:" + typeKey;
            }
        } catch (Throwable ignored) {
            // Fall through to class name.
        }
        return structure.getClass().getName();
    }

    @Unique
    private boolean bts$processWeightedSelection(
            List<StructureSet.StructureSelectionEntry> entries,
            StructureManager pStructureManager,
            RegistryAccess pRegistryAccess,
            RandomState pRandom,
            StructureTemplateManager pStructureTemplateManager,
            long pSeed,
            ChunkAccess pChunk,
            ChunkPos pChunkPos,
            SectionPos pSectionPos
    ) {
        long selectionStart = StructureStartMetrics.startTimer();
        int rolls = 0;
        int candidateScans = 0;
        int count = entries.size();
        try {
            int[] indices = INDEX_POOL.get();
            if (count > indices.length) {
                indices = new int[count]; // Rare growth case.
                INDEX_POOL.set(indices);
            }

            int totalWeight = 0;
            for (int i = 0; i < count; i++) {
                indices[i] = i;
                totalWeight += entries.get(i).weight();
            }

            WorldgenRandom random = SHARED_RANDOM.get();
            random.setLargeFeatureSeed(pSeed, pChunkPos.x, pChunkPos.z);

            int remaining = count;
            while (remaining > 0) {
                rolls++;
                int roll = random.nextInt(totalWeight);
                int currentSum = 0;
                int pickedIdxInArray = -1;

                for (int i = 0; i < remaining; i++) {
                    candidateScans++;
                    int entryIdx = indices[i];
                    currentSum += entries.get(entryIdx).weight();
                    if (roll < currentSum) {
                        pickedIdxInArray = i;
                        break;
                    }
                }

                int pickedEntryIdx = indices[pickedIdxInArray];
                if (this.bts$tryGenerateStructureTimed(entries.get(pickedEntryIdx), pStructureManager, pRegistryAccess,
                        pRandom, pStructureTemplateManager, pSeed, pChunk, pChunkPos, pSectionPos)) {
                    return true;
                }

                // O(1) removal: replace the picked slot with the last live slot.
                totalWeight -= entries.get(pickedEntryIdx).weight();
                indices[pickedIdxInArray] = indices[remaining - 1];
                remaining--;
            }
            return false;
        } finally {
            StructureStartMetrics.recordWeightedSelection(selectionStart, count, rolls, candidateScans);
        }
    }
}
