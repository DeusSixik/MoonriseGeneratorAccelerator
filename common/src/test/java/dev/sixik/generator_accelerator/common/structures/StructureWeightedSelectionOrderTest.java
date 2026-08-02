package dev.sixik.generator_accelerator.common.structures;

import dev.sixik.generator_accelerator.common.structures.mixin.MixinChunkGenerator$optimize_creating_structure;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class StructureWeightedSelectionOrderTest {

    @Test
    void weightedSelectionAttemptsMatchVanillaRemoveOrderAfterFailures() throws Exception {
        List<StructureSet.StructureSelectionEntry> entries = entries(1, 4, 2, 8, 3, 5);
        long[] seeds = {0L, 1L, 42L, 0x1234_5678_9ABC_DEF0L, -7L};
        ChunkPos[] positions = {new ChunkPos(0, 0), new ChunkPos(12, -9), new ChunkPos(-12345, 67890)};
        Method processWeightedSelection = processWeightedSelectionMethod();

        for (long seed : seeds) {
            for (ChunkPos pos : positions) {
                RecordingGenerator generator = new RecordingGenerator();
                processWeightedSelection.invoke(generator, entries, null, null, null, null, seed, null, pos, null);

                List<StructureSet.StructureSelectionEntry> expected = vanillaAttemptOrder(entries, seed, pos);
                assertSameOrder(expected, generator.attempts, "seed=" + seed + ", pos=" + pos);
            }
        }
    }

    private static Method processWeightedSelectionMethod() throws NoSuchMethodException {
        Method method = MixinChunkGenerator$optimize_creating_structure.class.getDeclaredMethod(
                "bts$processWeightedSelection",
                List.class,
                StructureManager.class,
                RegistryAccess.class,
                RandomState.class,
                StructureTemplateManager.class,
                long.class,
                ChunkAccess.class,
                ChunkPos.class,
                SectionPos.class
        );
        method.setAccessible(true);
        return method;
    }

    private static List<StructureSet.StructureSelectionEntry> vanillaAttemptOrder(
            List<StructureSet.StructureSelectionEntry> entries,
            long seed,
            ChunkPos pos
    ) {
        ArrayList<StructureSet.StructureSelectionEntry> remaining = new ArrayList<>(entries);
        ArrayList<StructureSet.StructureSelectionEntry> order = new ArrayList<>(entries.size());
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, pos.x, pos.z);

        int totalWeight = 0;
        for (StructureSet.StructureSelectionEntry entry : remaining) {
            totalWeight += entry.weight();
        }

        while (!remaining.isEmpty()) {
            int roll = random.nextInt(totalWeight);
            int currentSum = 0;
            int pickedIndex = -1;
            for (int i = 0; i < remaining.size(); i++) {
                StructureSet.StructureSelectionEntry entry = remaining.get(i);
                currentSum += entry.weight();
                if (roll < currentSum) {
                    pickedIndex = i;
                    break;
                }
            }

            StructureSet.StructureSelectionEntry picked = remaining.remove(pickedIndex);
            totalWeight -= picked.weight();
            order.add(picked);
        }

        return order;
    }

    private static List<StructureSet.StructureSelectionEntry> entries(int... weights) {
        ArrayList<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(weights.length);
        for (int i = 0; i < weights.length; i++) {
            Structure structure = mock(Structure.class, withSettings().stubOnly().name("structure-" + i));
            entries.add(new StructureSet.StructureSelectionEntry(Holder.direct(structure), weights[i]));
        }
        return entries;
    }

    private static void assertSameOrder(
            List<StructureSet.StructureSelectionEntry> expected,
            List<StructureSet.StructureSelectionEntry> actual,
            String message
    ) {
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i), message + ", index=" + i);
        }
    }

    private static final class RecordingGenerator extends MixinChunkGenerator$optimize_creating_structure {
        private final ArrayList<StructureSet.StructureSelectionEntry> attempts = new ArrayList<>();

        @Override
        protected boolean tryGenerateStructure(
                StructureSet.StructureSelectionEntry arg,
                StructureManager arg2,
                RegistryAccess arg3,
                RandomState arg4,
                StructureTemplateManager arg5,
                long l,
                ChunkAccess arg6,
                ChunkPos arg7,
                SectionPos arg8
        ) {
            attempts.add(arg);
            return false;
        }
    }
}
