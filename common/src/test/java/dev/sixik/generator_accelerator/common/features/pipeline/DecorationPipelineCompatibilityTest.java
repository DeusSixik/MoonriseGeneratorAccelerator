package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.BeforeAll;
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

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void quarantineMarksOnlyTheFailingPlacedFeatureInstance() {
        PlacedFeature failingFeature = placed(Feature.TREE, FeatureConfiguration.NONE);
        PlacedFeature unaffectedFeature = placed(Feature.TREE, FeatureConfiguration.NONE);
        DecorationKernelPlan kernel = DecorationKernelPlan.vanillaFallback(failingFeature, 7);
        Registry<PlacedFeature> registry = mock(Registry.class);
        when(registry.getResourceKey(failingFeature)).thenReturn(Optional.of(
                ResourceKey.create(Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath("brokenmod", "bad_tree"))
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PlacedFeature placed(Feature<?> feature, FeatureConfiguration config) {
        ConfiguredFeature configuredFeature = new ConfiguredFeature((Feature) feature, config);
        return new PlacedFeature(Holder.direct(configuredFeature), List.of());
    }
}
