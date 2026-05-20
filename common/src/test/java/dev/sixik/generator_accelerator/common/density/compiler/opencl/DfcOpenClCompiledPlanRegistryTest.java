package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DfcOpenClCompiledPlanRegistryTest {
    @Test
    void planExternRetentionRejectsNoiseChunkLocalExterns() {
        assertFalse(DfcOpenClCompiledPlanRegistry.reboundExternTypeRetainable(
                "net.minecraft.world.level.levelgen.DensityFunctions$Beardifier",
                List.of("net.minecraft.world.level.levelgen.DensityFunctions$BeardifierOrMarker")));
        assertFalse(DfcOpenClCompiledPlanRegistry.reboundExternTypeRetainable(
                "net.minecraft.world.level.levelgen.NoiseChunk$NoiseInterpolator",
                List.of("net.minecraft.world.level.levelgen.NoiseChunk$NoiseChunkDensityFunction")));
        assertTrue(DfcOpenClCompiledPlanRegistry.reboundExternTypeRetainable(
                "example.SafeDensityFunction",
                List.of("example.SafeInterface")));
    }
}
