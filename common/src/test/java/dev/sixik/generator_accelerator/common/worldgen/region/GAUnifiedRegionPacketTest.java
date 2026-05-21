package dev.sixik.generator_accelerator.common.worldgen.region;

import dev.sixik.generator_accelerator.common.aquifer.region.GARegionalAquiferAtlas;
import dev.sixik.generator_accelerator.common.aquifer.region.GARegionalAquiferAtlasOwner;
import dev.sixik.generator_accelerator.common.biome.region.GARegionalClimateQuartRaster;
import dev.sixik.generator_accelerator.common.noise.region.GARegionalDensitySliceCacheOwner;
import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GAUnifiedRegionPacketTest {
    @Test
    void terrainBindKeepsDensityAndAquiferViewsLocalToRegion() {
        GAUnifiedRegionPacket packet = new GAUnifiedRegionPacket();
        GARegionalDensitySliceCacheOwner densityOwner = new GARegionalDensitySliceCacheOwner(
                new Object(),
                new Object(),
                4,
                8,
                4,
                48,
                -8,
                new Object[]{new Object()}
        );
        GARegionalAquiferAtlasOwner aquiferOwner = new GARegionalAquiferAtlasOwner(
                null,
                (x, y, z) -> null,
                new Object(),
                new Object(),
                new Object(),
                0,
                0,
                0,
                4,
                4
        );

        packet.bindTerrain(32, 48, densityOwner, aquiferOwner);

        assertEquals(0, packet.regionX());
        assertEquals(0, packet.regionZ());
        assertNotNull(packet.densityView());
        assertNotNull(packet.aquiferView());

        packet.bindTerrain(96, 48, null, null);

        assertEquals(1, packet.regionX());
        assertEquals(0, packet.regionZ());
        assertNotNull(packet.densityView());
        assertNotNull(packet.aquiferView());
    }

    @Test
    void resetClearsRegionalHandles() {
        GAUnifiedRegionPacket packet = new GAUnifiedRegionPacket();
        packet.reset();

        assertNull(packet.densityView());
        assertNull(packet.aquiferView());
        assertNull(packet.beardifierView());
        assertNull(packet.climateView());
        assertNull(packet.noiseBrickView());
    }

    @Test
    void climateBindRefreshesViewWhenChunkMovesToAnotherRegion() {
        GAUnifiedRegionPacket packet = new GAUnifiedRegionPacket();
        BiomeManager.NoiseBiomeSource source = (quartX, quartY, quartZ) -> null;

        packet.bindClimate(source, source, null, null, 0, 0, 0, GARegionalClimateQuartRaster.quartCount(8));
        GARegionalClimateQuartRaster.View first = packet.climateView();

        packet.bindClimate(source, source, null, null, 64, 0, 0, GARegionalClimateQuartRaster.quartCount(8));
        GARegionalClimateQuartRaster.View second = packet.climateView();

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
        assertEquals(1, packet.regionX());
    }
}
