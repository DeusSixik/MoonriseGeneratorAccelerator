package dev.sixik.generator_accelerator.api.structures;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FastBiomeCacheTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void assignsDenseIdsForBiomeHoldersAndBiomes() throws Exception {
        Biome biome = mock(Biome.class, org.mockito.Mockito.withSettings().stubOnly());
        Holder<Biome> holder = Holder.direct(biome);
        @SuppressWarnings("unchecked")
        Registry<Biome> registry = mock(Registry.class);
        when(registry.size()).thenReturn(1);
        when(registry.iterator()).thenReturn(List.of(biome).iterator());
        when(registry.wrapAsHolder(biome)).thenReturn(holder);

        Class<?> cacheClass = Class.forName("dev.sixik.generator_accelerator.api.structures.FastBiomeCache");
        cacheClass.getMethod("init", Registry.class).invoke(null, registry);

        int holderId = (Integer) cacheClass.getMethod("getId", Holder.class).invoke(null, holder);
        int biomeId = (Integer) cacheClass.getMethod("getId", Biome.class).invoke(null, biome);
        assertTrue(holderId >= 0);
        assertEquals(holderId, biomeId);
        assertSame(holder, cacheClass.getMethod("getBiomeHolder", int.class).invoke(null, holderId));
        assertSame(biome, cacheClass.getMethod("getBiome", int.class).invoke(null, holderId));

        Class<?> extensionClass = Class.forName("dev.sixik.generator_accelerator.api.patches.GA$BiomeExtension");
        Object extension = extensionClass.getMethod("get", Biome.class).invoke(null, biome);
        int fastId = (Integer) extensionClass.getMethod("bts$getFastId").invoke(extension);
        assertEquals(holderId, fastId);
    }
}
