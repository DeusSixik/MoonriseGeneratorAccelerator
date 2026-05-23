package dev.sixik.generator_accelerator.api.structures;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FastStructureCacheTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void assignsDenseIdsAndExposesFallbackFastIdAccess() throws Exception {
        Structure structure = new DummyStructure();
        @SuppressWarnings("unchecked")
        Registry<Structure> registry = mock(Registry.class);
        when(registry.size()).thenReturn(1);
        when(registry.iterator()).thenReturn(List.of(structure).iterator());

        Class<?> cacheClass = Class.forName("dev.sixik.generator_accelerator.api.structures.FastStructureCache");
        cacheClass.getMethod("init", Registry.class).invoke(null, registry);

        Method getId = cacheClass.getMethod("getId", Structure.class);
        int id = (Integer) getId.invoke(null, structure);
        assertTrue(id >= 0);
        assertSame(structure, cacheClass.getMethod("getStructure", int.class).invoke(null, id));

        Class<?> extensionClass = Class.forName("dev.sixik.generator_accelerator.api.patches.GA$StructureExtension");
        Object extension = extensionClass.getMethod("get", Structure.class).invoke(null, structure);
        int fastId = (Integer) extensionClass.getMethod("bts$getFastId").invoke(extension);
        assertEquals(id, fastId);
    }

    private static final class DummyStructure extends Structure {
        private DummyStructure() {
            super(new Structure.StructureSettings(
                    HolderSet.direct(Holder.direct(mock(net.minecraft.world.level.biome.Biome.class, org.mockito.Mockito.withSettings().stubOnly()))),
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
