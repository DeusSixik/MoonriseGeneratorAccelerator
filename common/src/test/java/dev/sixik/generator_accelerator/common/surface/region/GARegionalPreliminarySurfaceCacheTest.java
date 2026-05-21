package dev.sixik.generator_accelerator.common.surface.region;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GARegionalPreliminarySurfaceCacheTest {
    static {
        System.setProperty("ga.surface.regionalPreliminaryCache.enabled", "true");
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void clearCache() {
        GARegionalPreliminarySurfaceCache.clearForTests();
    }

    @Test
    void reusesQuartTileAcrossSameRegion() {
        DensityFunction density = Mockito.mock(DensityFunction.class);
        NoiseSettings noiseSettings = Mockito.mock(NoiseSettings.class);
        Mockito.when(noiseSettings.minY()).thenReturn(0);
        Mockito.when(noiseSettings.height()).thenReturn(64);
        Mockito.when(density.compute(Mockito.any()))
                .thenAnswer(invocation -> {
                    DensityFunction.FunctionContext ctx = invocation.getArgument(0, DensityFunction.FunctionContext.class);
                    return ctx.blockY() >= 40 ? 1.0D : -1.0D;
                });

        assertEquals(64, GARegionalPreliminarySurfaceCache.sample(density, noiseSettings, 8, 0, 0));
        assertEquals(64, GARegionalPreliminarySurfaceCache.sample(density, noiseSettings, 8, 28, 12));
        assertEquals(64, GARegionalPreliminarySurfaceCache.sample(density, noiseSettings, 8, 60, 60));
        assertEquals(1, GARegionalPreliminarySurfaceCache.cacheSize());
        Mockito.verify(density, Mockito.times(256)).compute(Mockito.any());
    }

    @Test
    void buildsSecondQuartTileForNextRegion() {
        DensityFunction density = Mockito.mock(DensityFunction.class);
        NoiseSettings noiseSettings = Mockito.mock(NoiseSettings.class);
        Mockito.when(noiseSettings.minY()).thenReturn(0);
        Mockito.when(noiseSettings.height()).thenReturn(32);
        Mockito.when(density.compute(Mockito.any()))
                .thenAnswer(invocation -> {
                    DensityFunction.FunctionContext ctx = invocation.getArgument(0, DensityFunction.FunctionContext.class);
                    return ctx.blockY() >= 16 ? 1.0D : -1.0D;
                });

        GARegionalPreliminarySurfaceCache.sample(density, noiseSettings, 8, 0, 0);
        GARegionalPreliminarySurfaceCache.sample(density, noiseSettings, 8, 64, 0);

        assertEquals(2, GARegionalPreliminarySurfaceCache.cacheSize());
        Mockito.verify(density, Mockito.times(512)).compute(Mockito.any());
    }
}
