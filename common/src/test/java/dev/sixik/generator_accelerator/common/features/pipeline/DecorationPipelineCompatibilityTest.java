package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecorationPipelineCompatibilityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrapHelper.ensureBootstrapped();
    }

    @BeforeEach
    void clearCompatibilityState() {
        DecorationPipelineCompatibility.clearSessionCaches();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void quarantineMarksOnlyTheFailingPlacedFeatureInstance() {
        PlacedFeature failingFeature = simpleBlock();
        PlacedFeature unaffectedFeature = simpleBlock();
        DecorationKernelPlan kernel = DecorationKernelPlan.vanillaFallback(failingFeature, 7);
        Registry<PlacedFeature> registry = mock(Registry.class);
        when(registry.getResourceKey(failingFeature)).thenReturn(Optional.of(
                ResourceKey.create(Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath("brokenmod", "bad_rock"))
        ));

        assertFalse(DecorationPipelineCompatibility.shouldUseSafeVanilla(failingFeature));
        assertFalse(DecorationPipelineCompatibility.shouldUseSafeVanilla(unaffectedFeature));

        DecorationPipelineCompatibility.quarantine(
                registry,
                failingFeature,
                kernel,
                3,
                11,
                new IndexOutOfBoundsException("Index -1 out of bounds 28499")
        );

        assertTrue(DecorationPipelineCompatibility.shouldUseSafeVanilla(failingFeature));
        assertFalse(DecorationPipelineCompatibility.shouldUseSafeVanilla(unaffectedFeature));
    }

    @Test
    void treeFeaturesUseSafeVanillaBeforeAnyQuarantine() {
        PlacedFeature tree = placed(Feature.TREE, FeatureConfiguration.NONE);

        assertTrue(DecorationPipelineCompatibility.isTreeLikeFeature(tree));
        assertTrue(DecorationPipelineCompatibility.shouldUseSafeVanilla(tree));
    }

    @Test
    void randomPatchTreeFeaturesUseSafeVanillaBeforeAnyQuarantine() {
        PlacedFeature tree = placed(Feature.TREE, FeatureConfiguration.NONE);
        PlacedFeature patch = placed(
                Feature.RANDOM_PATCH,
                new RandomPatchConfiguration(8, 3, 3, Holder.direct(tree))
        );

        assertTrue(DecorationPipelineCompatibility.isTreeLikeFeature(patch));
        assertTrue(DecorationPipelineCompatibility.shouldUseSafeVanilla(patch));
    }

    private static PlacedFeature simpleBlock() {
        return placed(
                Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.STONE))
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PlacedFeature placed(Feature<?> feature, FeatureConfiguration config) {
        ConfiguredFeature configuredFeature = new ConfiguredFeature((Feature) feature, config);
        return new PlacedFeature(Holder.direct(configuredFeature), List.of());
    }
}
