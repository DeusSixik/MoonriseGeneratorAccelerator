package dev.sixik.generator_accelerator.common.surface.region;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GARegionalSurfaceNoiseCacheTest {
    static {
        System.setProperty("ga.surface.regionalNoiseCache.enabled", "true");
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void clearCache() {
        GARegionalSurfaceNoiseCache.clearForTests();
    }

    @Test
    void reusesExactValuesAcrossAllChunksInsideSingleRegion() {
        RandomState randomState = Mockito.mock(RandomState.class);
        NormalNoise noise = Mockito.mock(NormalNoise.class);
        Mockito.when(randomState.getOrCreateNoise(Noises.SURFACE)).thenReturn(noise);
        Mockito.when(noise.getValue(Mockito.anyDouble(), Mockito.eq(0.0D), Mockito.anyDouble()))
                .thenAnswer(invocation -> valueAt(
                        invocation.getArgument(0, Double.class),
                        invocation.getArgument(2, Double.class)
                ));

        assertEquals(valueAt(3.0D, 5.0D), GARegionalSurfaceNoiseCache.sample(randomState, Noises.SURFACE, 3, 5), 0.0D);
        assertEquals(valueAt(17.0D, 9.0D), GARegionalSurfaceNoiseCache.sample(randomState, Noises.SURFACE, 17, 9), 0.0D);
        assertEquals(valueAt(47.0D, 31.0D), GARegionalSurfaceNoiseCache.sample(randomState, Noises.SURFACE, 47, 31), 0.0D);
        assertEquals(valueAt(63.0D, 63.0D), GARegionalSurfaceNoiseCache.sample(randomState, Noises.SURFACE, 63, 63), 0.0D);
        assertEquals(1, GARegionalSurfaceNoiseCache.cacheSize());
        Mockito.verify(noise, Mockito.times(4096))
                .getValue(Mockito.anyDouble(), Mockito.eq(0.0D), Mockito.anyDouble());
    }

    @Test
    void buildsSeparateTilesForSeparateRegions() {
        RandomState randomState = Mockito.mock(RandomState.class);
        NormalNoise noise = Mockito.mock(NormalNoise.class);
        Mockito.when(randomState.getOrCreateNoise(Noises.SURFACE)).thenReturn(noise);
        Mockito.when(noise.getValue(Mockito.anyDouble(), Mockito.eq(0.0D), Mockito.anyDouble()))
                .thenAnswer(invocation -> valueAt(
                        invocation.getArgument(0, Double.class),
                        invocation.getArgument(2, Double.class)
                ));

        GARegionalSurfaceNoiseCache.sample(randomState, Noises.SURFACE, 8, 8);
        GARegionalSurfaceNoiseCache.sample(randomState, Noises.SURFACE, 72, 8);

        assertEquals(2, GARegionalSurfaceNoiseCache.cacheSize());
        Mockito.verify(noise, Mockito.times(8192))
                .getValue(Mockito.anyDouble(), Mockito.eq(0.0D), Mockito.anyDouble());
    }

    private static double valueAt(double x, double z) {
        return (x * 0.125D) - (z * 0.0625D);
    }
}
