package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StructureNoiseColumnCacheTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void baseHeightKeysAreExact() {
        System.setProperty("ga.structureNoiseColumnCache.enabled", "true");
        Object generator = new Object();
        Object randomState = new Object();
        Object settings = new Object();
        LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(-64, 384);
        NoiseSettings noiseSettings = NoiseSettings.create(-64, 384, 1, 2);

        try (StructureNoiseColumnCache cache = StructureNoiseColumnCache.enter()) {
            cache.putBaseHeight(
                    generator,
                    10,
                    -20,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    heightAccessor,
                    randomState,
                    settings,
                    noiseSettings,
                    72
            );

            assertEquals(72, cache.getBaseHeight(
                    generator,
                    10,
                    -20,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    heightAccessor,
                    randomState,
                    settings,
                    noiseSettings
            ));
            assertEquals(StructureNoiseColumnCache.MISS, cache.getBaseHeight(
                    generator,
                    10,
                    -19,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    heightAccessor,
                    randomState,
                    settings,
                    noiseSettings
            ));
            assertEquals(StructureNoiseColumnCache.MISS, cache.getBaseHeight(
                    generator,
                    10,
                    -20,
                    Heightmap.Types.OCEAN_FLOOR_WG,
                    heightAccessor,
                    randomState,
                    settings,
                    noiseSettings
            ));
            assertEquals(StructureNoiseColumnCache.MISS, cache.getBaseHeight(
                    generator,
                    10,
                    -20,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    heightAccessor,
                    new Object(),
                    settings,
                    noiseSettings
            ));
        }

        assertNull(StructureNoiseColumnCache.current());
    }

    @Test
    void baseColumnHitsAreDefensiveCopiesAndFeedHeight() {
        System.setProperty("ga.structureNoiseColumnCache.enabled", "true");
        Object generator = new Object();
        Object randomState = new Object();
        Object settings = new Object();
        LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(-64, 384);
        NoiseSettings noiseSettings = NoiseSettings.create(-64, 384, 1, 2);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState[] states = new BlockState[noiseSettings.height()];
        Arrays.fill(states, air);
        states[70 - noiseSettings.minY()] = stone;

        try (StructureNoiseColumnCache cache = StructureNoiseColumnCache.enter()) {
            cache.putBaseColumn(generator, 4, 9, heightAccessor, randomState, settings, noiseSettings, states);

            NoiseColumn first = cache.getBaseColumn(generator, 4, 9, heightAccessor, randomState, settings, noiseSettings);
            assertEquals(stone, first.getBlock(70));
            first.setBlock(70, air);

            NoiseColumn second = cache.getBaseColumn(generator, 4, 9, heightAccessor, randomState, settings, noiseSettings);
            assertEquals(stone, second.getBlock(70));
            assertEquals(71, cache.getBaseHeight(
                    generator,
                    4,
                    9,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    heightAccessor,
                    randomState,
                    settings,
                    noiseSettings
            ));
        }
    }
}
