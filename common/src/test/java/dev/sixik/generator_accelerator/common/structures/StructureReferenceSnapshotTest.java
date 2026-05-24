package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureReferenceSnapshotTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void copiedReferencesStayIsolatedFromLaterMutations() throws Exception {
        Structure structure = new DummyStructure();
        LongOpenHashSet originalReferences = new LongOpenHashSet(new long[]{11L, 29L});
        Map<Structure, LongSet> original = new Reference2ObjectOpenHashMap<>();
        original.put(structure, originalReferences);

        Class<?> snapshotClass = Class.forName(
                "dev.sixik.generator_accelerator.common.structures.StructureReferenceSnapshot"
        );
        Method copyReferences = snapshotClass.getMethod("copyReferences", Map.class);

        @SuppressWarnings("unchecked")
        Map<Structure, LongSet> snapshot = (Map<Structure, LongSet>) copyReferences.invoke(null, original);
        LongSet copiedReferences = snapshot.get(structure);

        originalReferences.add(47L);

        assertEquals(2, copiedReferences.size());
        assertTrue(copiedReferences.contains(11L));
        assertTrue(copiedReferences.contains(29L));
        assertFalse(copiedReferences.contains(47L));
        assertThrows(UnsupportedOperationException.class, () -> copiedReferences.add(53L));
    }

    private static final class DummyStructure extends Structure {
        private DummyStructure() {
            super(new Structure.StructureSettings(
                    HolderSet.direct(Holder.direct(Mockito.mock(net.minecraft.world.level.biome.Biome.class, Mockito.withSettings().stubOnly()))),
                    Map.<MobCategory, StructureSpawnOverride>of(),
                    GenerationStep.Decoration.SURFACE_STRUCTURES,
                    TerrainAdjustment.NONE
            ));
        }

        @Override
        protected Optional<GenerationStub> findGenerationPoint(GenerationContext generationContext) {
            return Optional.empty();
        }

        @Override
        public StructureType<?> type() {
            return null;
        }
    }
}
