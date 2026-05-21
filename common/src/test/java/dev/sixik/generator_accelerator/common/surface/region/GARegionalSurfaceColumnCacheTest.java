package dev.sixik.generator_accelerator.common.surface.region;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GARegionalSurfaceColumnCacheTest {
    static {
        System.setProperty("ga.surface.regionalColumnCache.enabled", "true");
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void clearCaches() {
        GARegionalSurfaceColumnCache.clearForTests();
    }

    @Test
    void reusesExactSurfaceDepthTileAcrossChunksInsideRegion() {
        SurfaceSystem surfaceSystem = Mockito.mock(SurfaceSystem.class);
        Mockito.when(surfaceSystem.getSurfaceDepth(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(invocation -> {
                    int x = invocation.getArgument(0, Integer.class);
                    int z = invocation.getArgument(1, Integer.class);
                    return (x * 3) - (z * 2);
                });

        int[] first = new int[256];
        int[] second = new int[256];
        GARegionalSurfaceColumnCache.copySurfaceDepths(surfaceSystem, 0, 0, first);
        GARegionalSurfaceColumnCache.copySurfaceDepths(surfaceSystem, 16, 16, second);

        assertEquals(1, GARegionalSurfaceColumnCache.depthCacheSize());
        assertEquals((0 * 3) - (0 * 2), first[0]);
        assertEquals((16 * 3) - (16 * 2), second[0]);
        Mockito.verify(surfaceSystem, Mockito.times(4096))
                .getSurfaceDepth(Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void reusesExactSecondarySurfaceTileAcrossChunksInsideRegion() {
        SurfaceSystem surfaceSystem = Mockito.mock(SurfaceSystem.class);
        Mockito.when(surfaceSystem.getSurfaceSecondary(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(invocation -> {
                    int x = invocation.getArgument(0, Integer.class);
                    int z = invocation.getArgument(1, Integer.class);
                    return (x * 0.25D) + (z * 0.5D);
                });

        double[] first = new double[256];
        double[] second = new double[256];
        GARegionalSurfaceColumnCache.copySecondarySurfaceNoises(surfaceSystem, 0, 0, first);
        GARegionalSurfaceColumnCache.copySecondarySurfaceNoises(surfaceSystem, 48, 48, second);

        assertEquals(1, GARegionalSurfaceColumnCache.secondaryCacheSize());
        assertEquals(0.0D, first[0], 0.0D);
        assertEquals((48 * 0.25D) + (48 * 0.5D), second[0], 0.0D);
        Mockito.verify(surfaceSystem, Mockito.times(4096))
                .getSurfaceSecondary(Mockito.anyInt(), Mockito.anyInt());
    }
}
