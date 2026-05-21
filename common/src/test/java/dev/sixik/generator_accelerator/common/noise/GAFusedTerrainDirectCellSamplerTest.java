package dev.sixik.generator_accelerator.common.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAFusedTerrainDirectCellSamplerTest {
    private static final int STONE_ID = 7;
    private static final int AIR_ID = 0;

    @Test
    void positiveDensityReturnsDefaultOutsideOreRange() {
        long packed = GAFusedTerrainDirectCellSampler.samplePacked(
                new double[]{0.25D},
                STONE_ID,
                AIR_ID,
                120,
                0,
                true,
                false,
                false
        );

        assertFalse(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed));
        assertEquals(STONE_ID, GAFusedTerrainNoiseChunkAccess.ga$packedBlockId(packed));
        assertFalse(GAFusedTerrainNoiseChunkAccess.ga$packedScheduleFluidUpdate(packed));
    }

    @Test
    void positiveDensityFallsBackInsideOreRangeWhenOrePreserved() {
        long packed = GAFusedTerrainDirectCellSampler.samplePacked(
                new double[]{0.25D},
                STONE_ID,
                AIR_ID,
                16,
                0,
                true,
                false,
                false
        );

        assertTrue(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed));
        assertEquals(
                GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_ORE_VEIN_RANGE,
                GAFusedTerrainNoiseChunkAccess.ga$packedFallbackReason(packed)
        );
    }

    @Test
    void positiveDensityCanSkipOreVeinPreservation() {
        long packed = GAFusedTerrainDirectCellSampler.samplePacked(
                new double[]{0.25D},
                STONE_ID,
                AIR_ID,
                16,
                0,
                true,
                true,
                false
        );

        assertFalse(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed));
        assertEquals(STONE_ID, GAFusedTerrainNoiseChunkAccess.ga$packedBlockId(packed));
    }

    @Test
    void nonSolidFallsBackUnlessAirShortcutEnabled() {
        long fallback = GAFusedTerrainDirectCellSampler.samplePacked(
                new double[]{-0.01D},
                STONE_ID,
                AIR_ID,
                120,
                0,
                false,
                false,
                false
        );
        long air = GAFusedTerrainDirectCellSampler.samplePacked(
                new double[]{-0.01D},
                STONE_ID,
                AIR_ID,
                120,
                0,
                false,
                false,
                true
        );

        assertTrue(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(fallback));
        assertEquals(
                GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_NON_SOLID,
                GAFusedTerrainNoiseChunkAccess.ga$packedFallbackReason(fallback)
        );
        assertFalse(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(air));
        assertEquals(AIR_ID, GAFusedTerrainNoiseChunkAccess.ga$packedBlockId(air));
    }

    @Test
    void outOfBoundsReportsReason() {
        long packed = GAFusedTerrainDirectCellSampler.samplePacked(
                new double[]{0.25D},
                STONE_ID,
                AIR_ID,
                120,
                1,
                false,
                false,
                false
        );

        assertTrue(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed));
        assertEquals(
                GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_OUT_OF_BOUNDS,
                GAFusedTerrainNoiseChunkAccess.ga$packedFallbackReason(packed)
        );
    }

    @Test
    void oreVeinRangeMatchesVanillaBands() {
        assertTrue(GAFusedTerrainDirectCellSampler.oreVeinCanReplaceAt(-60));
        assertTrue(GAFusedTerrainDirectCellSampler.oreVeinCanReplaceAt(-8));
        assertFalse(GAFusedTerrainDirectCellSampler.oreVeinCanReplaceAt(-7));
        assertFalse(GAFusedTerrainDirectCellSampler.oreVeinCanReplaceAt(-1));
        assertTrue(GAFusedTerrainDirectCellSampler.oreVeinCanReplaceAt(0));
        assertTrue(GAFusedTerrainDirectCellSampler.oreVeinCanReplaceAt(50));
        assertFalse(GAFusedTerrainDirectCellSampler.oreVeinCanReplaceAt(51));
    }

    @Test
    void allPositiveCellCanBypassPerBlockSamplingOutsideOreRange() {
        assertTrue(GAFusedTerrainDirectCellSampler.cellCanUseDefaultSolid(
                new double[]{0.1D, 0.2D, 1.0D},
                80,
                4,
                true,
                false
        ));
    }

    @Test
    void allPositiveCellPreservesOreRangeUnlessExplicitlySkipped() {
        assertFalse(GAFusedTerrainDirectCellSampler.cellCanUseDefaultSolid(
                new double[]{0.1D, 0.2D, 1.0D},
                0,
                4,
                true,
                false
        ));
        assertTrue(GAFusedTerrainDirectCellSampler.cellCanUseDefaultSolid(
                new double[]{0.1D, 0.2D, 1.0D},
                0,
                4,
                true,
                true
        ));
    }

    @Test
    void cellBypassRejectsAnyNonSolidDensity() {
        assertFalse(GAFusedTerrainDirectCellSampler.cellCanUseDefaultSolid(
                new double[]{0.1D, -0.01D, 1.0D},
                80,
                4,
                false,
                false
        ));
    }
}
