package dev.sixik.generator_accelerator.common.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAFusedTerrainNoiseChunkAccessPackingTest {

    @Test
    void packsBlockIdAndScheduleBit() {
        long packed = GAFusedTerrainNoiseChunkAccess.ga$packFusedTerrain(123456789, true);

        assertFalse(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed));
        assertEquals(123456789, GAFusedTerrainNoiseChunkAccess.ga$packedBlockId(packed));
        assertTrue(GAFusedTerrainNoiseChunkAccess.ga$packedScheduleFluidUpdate(packed));
        assertEquals(
                GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_GENERIC,
                GAFusedTerrainNoiseChunkAccess.ga$packedFallbackReason(packed)
        );
    }

    @Test
    void packsFallbackReasonWithoutLookingLikeRealBlock() {
        long packed = GAFusedTerrainNoiseChunkAccess.ga$packFallback(
                GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_NON_SOLID
        );

        assertTrue(GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed));
        assertEquals(
                GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_NON_SOLID,
                GAFusedTerrainNoiseChunkAccess.ga$packedFallbackReason(packed)
        );
    }
}
