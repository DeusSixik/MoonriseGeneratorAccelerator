package dev.sixik.generator_accelerator.common.biome.region;

import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GARegionalClimateQuartRasterTest {
    @BeforeEach
    void clearCache() {
        GARegionalClimateQuartRaster.clearForTests();
    }

    @Test
    void quartRasterReusesEntryPerSemanticOwnerAndRegion() {
        BiomeManager.NoiseBiomeSource firstSource = (quartX, quartY, quartZ) -> null;
        BiomeManager.NoiseBiomeSource secondSource = (quartX, quartY, quartZ) -> null;
        Object biomeIdentity = new Object();
        GARegionalClimateQuartRasterOwner firstOwner = new GARegionalClimateQuartRasterOwner(
                biomeIdentity,
                firstSource,
                null,
                null,
                -2,
                3
        );
        GARegionalClimateQuartRasterOwner secondOwner = new GARegionalClimateQuartRasterOwner(
                biomeIdentity,
                secondSource,
                null,
                null,
                -2,
                3
        );

        GARegionalClimateQuartRaster.View first = GARegionalClimateQuartRaster.view(firstOwner, 0, 0);
        GARegionalClimateQuartRaster.View second = GARegionalClimateQuartRaster.view(secondOwner, 16, 16);

        assertNotNull(first);
        assertNotNull(second);
        assertNull(first.sampleNoiseBiome(0, -2, 0));

        Map<String, Object> snapshot = GARegionalClimateQuartRaster.snapshot();
        assertEquals(1L, ((Number) snapshot.get("builds")).longValue());
    }

    @Test
    void biomeManagerSamplingStaysScalarByDefault() {
        BiomeManager.NoiseBiomeSource source = (quartX, quartY, quartZ) -> null;

        assertNull(GARegionalClimateQuartRaster.sampleBiome(source, 4, 7, 9));

        Map<String, Object> snapshot = GARegionalClimateQuartRaster.snapshot();
        assertEquals(0L, ((Number) snapshot.get("builds")).longValue());
        assertEquals(0L, ((Number) snapshot.get("misses")).longValue());
    }
}
