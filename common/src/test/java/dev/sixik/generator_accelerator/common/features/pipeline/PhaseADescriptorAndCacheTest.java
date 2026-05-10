package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhaseADescriptorAndCacheTest {

    private final JavaDecorationCompiler compiler = new JavaDecorationCompiler();

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrapHelper.ensureBootstrapped();
    }

    @Test
    void compileStepTracksDescriptorFeaturesAcrossMaskWords() {
        Object[] features = new Object[65];
        for (int i = 0; i < features.length; i++) {
            features[i] = placed(Feature.TREE, FeatureConfiguration.NONE);
        }
        features[63] = placedSimpleBlock();
        features[64] = placedSimpleBlock();

        DecorationStepPlan stepPlan = this.compiler.compileStep(4, features);

        assertFalse(stepPlan.selectedNeedsDescriptors(new long[]{1L}, 1));
        assertTrue(stepPlan.selectedNeedsDescriptors(new long[]{1L << 63, 0L}, 2));
        assertTrue(stepPlan.selectedNeedsDescriptors(new long[]{0L, 1L}, 2));
        assertFalse(stepPlan.selectedNeedsDescriptors(new long[]{0L, 1L}, 1));
    }

    @Test
    void descriptorAndHeightCachesRebuildWhenChunkInstanceChangesAtSamePosition() {
        SectionDescriptorCache cache = new SectionDescriptorCache();
        ChunkPos pos = new ChunkPos(2, 3);
        ChunkAccess firstChunk = chunkAt(pos, 0, 0, singleBlockSection(1, 5, 2, Blocks.STONE.defaultBlockState()));
        ChunkAccess secondChunk = chunkAt(pos, 0, 0, singleBlockSection(1, 2, 2, Blocks.DIRT.defaultBlockState()));

        SectionDescriptor firstDescriptor = cache.getOrBuild(firstChunk, 0);
        assertEquals(5, firstDescriptor.columnHighestFilledBlockY(1, 2));
        assertEquals(6, cache.firstAvailableHeight(firstChunk, Heightmap.Types.WORLD_SURFACE_WG, 1, 2));
        assertEquals(6, cache.firstAvailableHeight(pos.x, pos.z, Heightmap.Types.WORLD_SURFACE_WG, 1, 2));

        SectionDescriptor rebuiltDescriptor = cache.getOrBuild(secondChunk, 0);
        assertSame(firstDescriptor, rebuiltDescriptor);
        assertEquals(2, rebuiltDescriptor.columnHighestFilledBlockY(1, 2));
        assertTrue(rebuiltDescriptor.columnHasBlockClassFlag(1, 2, SectionDescriptor.CLASS_DIRT_LIKE));
        assertFalse(rebuiltDescriptor.columnHasBlockClassFlag(1, 2, SectionDescriptor.CLASS_STONE_LIKE));
        assertEquals(3, cache.firstAvailableHeight(secondChunk, Heightmap.Types.WORLD_SURFACE_WG, 1, 2));
        assertEquals(3, cache.firstAvailableHeight(secondChunk, Heightmap.Types.OCEAN_FLOOR_WG, 1, 2));
        assertEquals(3, cache.firstAvailableHeight(pos.x, pos.z, Heightmap.Types.OCEAN_FLOOR_WG, 1, 2));
    }

    @Test
    void noteBlockMutationRepairsDescriptorAndHeightCachesForTouchedColumn() {
        SectionDescriptorCache cache = new SectionDescriptorCache();
        MutableSection section = new MutableSection();
        section.setBlock(1, 5, 2, Blocks.STONE.defaultBlockState());
        ChunkAccess chunk = chunkAt(new ChunkPos(0, 0), 0, 0, section.section());

        SectionDescriptor descriptor = cache.getOrBuild(chunk, 0);
        assertEquals(5, descriptor.columnHighestFilledBlockY(1, 2));
        assertEquals(6, cache.firstAvailableHeight(chunk, Heightmap.Types.WORLD_SURFACE_WG, 1, 2));
        assertEquals(6, cache.firstAvailableHeight(chunk, Heightmap.Types.OCEAN_FLOOR_WG, 1, 2));

        section.setBlock(1, 5, 2, Blocks.AIR.defaultBlockState());
        section.setBlock(1, 9, 2, Blocks.WATER.defaultBlockState());
        cache.noteBlockMutation(chunk, 1, 9, 2);

        assertSame(descriptor, cache.findByBlockPos(1, 9, 2));
        assertEquals(9, descriptor.columnHighestFilledBlockY(1, 2));
        assertTrue(descriptor.columnHasPaletteFlag(1, 2, SectionDescriptor.PALETTE_WATER));
        assertFalse(descriptor.columnHasBlockClassFlag(1, 2, SectionDescriptor.CLASS_STONE_LIKE));
        assertTrue(descriptor.columnHasOpenAt(1, 5, 2));
        assertEquals(10, cache.firstAvailableHeight(chunk, Heightmap.Types.WORLD_SURFACE_WG, 1, 2));
        assertEquals(0, cache.firstAvailableHeight(chunk, Heightmap.Types.OCEAN_FLOOR_WG, 1, 2));
        assertEquals(10, cache.firstAvailableHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, 1, 2));
    }

    @Test
    void topWaterHeightsTrackDescriptorColumnsAndMutations() {
        SectionDescriptorCache cache = new SectionDescriptorCache();
        MutableSection section = new MutableSection();
        section.setBlock(4, 3, 7, Blocks.STONE.defaultBlockState());
        section.setBlock(4, 8, 7, Blocks.WATER.defaultBlockState());
        section.setBlock(4, 10, 7, Blocks.WATER.defaultBlockState());
        ChunkAccess chunk = chunkAt(new ChunkPos(1, 1), 0, 0, section.section());

        SectionDescriptor descriptor = cache.getOrBuild(chunk, 0);
        assertTrue(descriptor.columnHasWaterAt(4, 8, 7));
        assertEquals(10, descriptor.columnHighestWaterBlockY(4, 7));
        assertEquals(10, cache.topWaterHeight(chunk, 4, 7));
        assertEquals(10, cache.topWaterHeight(1, 1, 4, 7));

        section.setBlock(4, 10, 7, Blocks.AIR.defaultBlockState());
        section.setBlock(4, 12, 7, Blocks.WATER.defaultBlockState());
        cache.noteBlockMutation(chunk, 4, 12, 7);

        assertEquals(12, descriptor.columnHighestWaterBlockY(4, 7));
        assertFalse(descriptor.columnHasWaterAt(4, 10, 7));
        assertTrue(descriptor.columnHasWaterAt(4, 12, 7));
        assertEquals(12, cache.topWaterHeight(chunk, 4, 7));
        assertEquals(12, cache.topWaterHeight(1, 1, 4, 7));
    }

    @Test
    void chunkColumnFlagsAggregateDescriptorFactsAcrossSectionColumns() {
        SectionDescriptorCache cache = new SectionDescriptorCache();
        MutableSection section = new MutableSection();
        section.setBlock(3, 2, 5, Blocks.DIRT.defaultBlockState());
        section.setBlock(3, 3, 5, Blocks.SHORT_GRASS.defaultBlockState());
        section.setBlock(3, 6, 5, Blocks.WATER.defaultBlockState());
        ChunkAccess chunk = chunkAt(new ChunkPos(4, 4), 0, 0, section.section());

        cache.buildChunk(chunk);
        SectionDescriptor descriptor = cache.getOrBuild(chunk, 0);

        int paletteFlags = cache.chunkColumnPaletteFlags(4, 4, 3, 5);
        int blockClassFlags = cache.chunkColumnBlockClassFlags(4, 4, 3, 5);
        assertTrue((paletteFlags & SectionDescriptor.PALETTE_AIR) != 0);
        assertTrue((paletteFlags & SectionDescriptor.PALETTE_WATER) != 0);
        assertTrue((blockClassFlags & SectionDescriptor.CLASS_DIRT_LIKE) != 0);
        assertTrue((blockClassFlags & SectionDescriptor.CLASS_REPLACEABLE) != 0);
        assertTrue(descriptor.columnHasGroundSupportAt(3, 2, 5));
        assertFalse(descriptor.columnHasGroundSupportAt(3, 3, 5));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PlacedFeature placed(Feature<?> feature, FeatureConfiguration config) {
        ConfiguredFeature configuredFeature = new ConfiguredFeature((Feature) feature, config);
        return new PlacedFeature(Holder.direct(configuredFeature), List.of());
    }

    private static PlacedFeature placedSimpleBlock() {
        return placed(
                Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.SHORT_GRASS))
        );
    }

    private static ChunkAccess chunkAt(ChunkPos pos, int minSection, int minBuildHeight, LevelChunkSection... sections) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(pos);
        when(chunk.getMinSection()).thenReturn(minSection);
        when(chunk.getMinBuildHeight()).thenReturn(minBuildHeight);
        when(chunk.getSections()).thenReturn(sections);
        return chunk;
    }

    private static LevelChunkSection singleBlockSection(int targetX, int targetY, int targetZ, BlockState targetState) {
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.hasOnlyAir()).thenReturn(false);
        when(section.getBlockState(anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int x = invocation.getArgument(0);
                    int y = invocation.getArgument(1);
                    int z = invocation.getArgument(2);
                    if (x == targetX && y == targetY && z == targetZ) {
                        return targetState;
                    }
                    return Blocks.AIR.defaultBlockState();
                });
        return section;
    }

    private static final class MutableSection {
        private final BlockState[][][] states = new BlockState[SectionDescriptor.SECTION_EDGE][SectionDescriptor.SECTION_EDGE][SectionDescriptor.SECTION_EDGE];
        private final LevelChunkSection section;

        private MutableSection() {
            for (int y = 0; y < SectionDescriptor.SECTION_EDGE; y++) {
                for (int z = 0; z < SectionDescriptor.SECTION_EDGE; z++) {
                    for (int x = 0; x < SectionDescriptor.SECTION_EDGE; x++) {
                        this.states[y][z][x] = Blocks.AIR.defaultBlockState();
                    }
                }
            }

            this.section = mock(LevelChunkSection.class);
            when(this.section.hasOnlyAir()).thenAnswer(invocation -> this.isAllAir());
            when(this.section.getBlockState(anyInt(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> this.states[invocation.getArgument(1)][invocation.getArgument(2)][invocation.getArgument(0)]);
        }

        private LevelChunkSection section() {
            return this.section;
        }

        private void setBlock(int x, int y, int z, BlockState state) {
            this.states[y][z][x] = state;
        }

        private boolean isAllAir() {
            for (int y = 0; y < SectionDescriptor.SECTION_EDGE; y++) {
                for (int z = 0; z < SectionDescriptor.SECTION_EDGE; z++) {
                    for (int x = 0; x < SectionDescriptor.SECTION_EDGE; x++) {
                        if (!this.states[y][z][x].isAir()) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }
}
