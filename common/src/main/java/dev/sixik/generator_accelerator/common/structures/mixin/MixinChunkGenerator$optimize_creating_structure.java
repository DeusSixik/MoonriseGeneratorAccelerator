package dev.sixik.generator_accelerator.common.structures.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$ChunkGeneratorStructureStateExtern;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator$optimize_creating_structure {

    @Unique
    private static final ThreadLocal<WorldgenRandom> SHARED_RANDOM =
            ThreadLocal.withInitial(() ->
                    new WorldgenRandom(new LegacyRandomSource(0L)
                    ));

    @Unique
    private static final ThreadLocal<int[]> INDEX_POOL =
            ThreadLocal.withInitial(() -> new int[32]);


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

        outer:
        for (int pSSize = 0; pSSize < possibleStructureSets.length; pSSize++) {
            final StructureSet set = possibleStructureSets[pSSize].value();
            StructurePlacement placement = set.placement();
            List<StructureSet.StructureSelectionEntry> structures = set.structures();

            /*
                Быстрая проверка: не занято ли место уже существующей структурой из этого сета
             */
            for (int structureIndex = 0; structureIndex < structures.size(); structureIndex++) {
                Structure structure = structures.get(structureIndex).structure().value();
                /*
                    Прямое обращение к карте чанка
                 */
                if (pChunk.getStartForStructure(structure) != null)
                    continue outer;
            }

            /*
                Проверка возможности размещения в этом чанке
             */
            if (placement.isStructureChunk(pStructureState, chunkpos.x, chunkpos.z)) {
                if (structures.size() == 1) {
                    this.tryGenerateStructure(structures.get(0), pStructureManager, pRegistryAccess,
                            randomstate, pStructureTemplateManager, pStructureState.getLevelSeed(),
                            pChunk, chunkpos, sectionpos);
                } else {
                    /*
                         DOD-подход:Weighted selection без ArrayList.remove()
                     */
                    bts$processWeightedSelection(structures, pStructureManager, pRegistryAccess,
                            randomstate, pStructureTemplateManager, pStructureState.getLevelSeed(),
                            pChunk, chunkpos, sectionpos);
                }
            }
        }
    }

    @Unique
    private void bts$processWeightedSelection(
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
        int count = entries.size();
        int[] indices = INDEX_POOL.get();
        if (count > indices.length) {
            indices = new int[count]; // Редкий случай расширения
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
            int roll = random.nextInt(totalWeight);
            int currentSum = 0;
            int pickedIdxInArray = -1;

            for (int i = 0; i < remaining; i++) {
                int entryIdx = indices[i];
                currentSum += entries.get(entryIdx).weight();
                if (roll < currentSum) {
                    pickedIdxInArray = i;
                    break;
                }
            }

            int pickedEntryIdx = indices[pickedIdxInArray];
            if (this.tryGenerateStructure(entries.get(pickedEntryIdx), pStructureManager, pRegistryAccess,
                    pRandom, pStructureTemplateManager, pSeed, pChunk, pChunkPos, pSectionPos)) {
                return;
            }

            // Удаление за O(1): ставим последний элемент на место выбранного
            totalWeight -= entries.get(pickedEntryIdx).weight();
            indices[pickedIdxInArray] = indices[remaining - 1];
            remaining--;
        }
    }
}
