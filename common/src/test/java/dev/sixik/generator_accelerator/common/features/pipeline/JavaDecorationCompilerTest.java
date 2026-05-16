package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenProfileMetrics;
import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenSafetyTier;
import net.minecraft.core.Holder;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SculkPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaDecorationCompilerTest {

    private final JavaDecorationCompiler compiler = new JavaDecorationCompiler();

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrapHelper.ensureBootstrapped();
    }

    @Test
    void treeLakeAndSculkClassificationMatchesPipelineSafetyRules() {
        PlacedFeature tree = placed(Feature.TREE, FeatureConfiguration.NONE);
        PlacedFeature lake = placed(
                Feature.LAKE,
                new LakeFeature.Configuration(
                        BlockStateProvider.simple(Blocks.WATER),
                        BlockStateProvider.simple(Blocks.STONE)
                )
        );
        PlacedFeature sculk = placed(
                Feature.SCULK_PATCH,
                new SculkPatchConfiguration(4, 10, 2, 1, 1, ConstantInt.of(0), 0.0F)
        );

        DecorationStepPlan stepPlan = this.compiler.compileStep(0, new Object[]{tree, lake, sculk});

        assertNotNull(stepPlan.kernelForFeatureIndex(0));
        assertNotNull(stepPlan.kernelForFeatureIndex(1));
        assertNotNull(stepPlan.kernelForFeatureIndex(2));
        assertEquals(DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT, stepPlan.kernelForFeatureIndex(0).kind());
        assertEquals(DecorationKernelKind.NATIVE_LAKE, stepPlan.kernelForFeatureIndex(1).kind());
        assertEquals(DecorationKernelKind.NATIVE_SCULK_PATCH, stepPlan.kernelForFeatureIndex(2).kind());
    }

    @Test
    void compileStepRecordsCheapClassifierCounters() {
        boolean previous = DecorationPipelineMetrics.ENABLED;
        boolean previousProfileMetrics = WorldgenProfileMetrics.ENABLED;
        DecorationPipelineMetrics.setEnabled(true);
        WorldgenProfileMetrics.setEnabled(true);
        DecorationPipelineMetrics.reset();
        WorldgenProfileMetrics.reset();
        try {
            PlacedFeature tree = placed(Feature.TREE, FeatureConfiguration.NONE);
            PlacedFeature lake = placed(
                    Feature.LAKE,
                    new LakeFeature.Configuration(
                            BlockStateProvider.simple(Blocks.WATER),
                            BlockStateProvider.simple(Blocks.STONE)
                    )
            );

            this.compiler.compileStep(0, new Object[]{tree, lake});

            assertEquals(1, DecorationPipelineMetrics.get(DecorationPipelineMetrics.CLASSIFIER_TIER1_UNITS));
            assertEquals(1, DecorationPipelineMetrics.get(DecorationPipelineMetrics.CLASSIFIER_TIER2_UNITS));
            assertEquals(1, DecorationPipelineMetrics.get(DecorationPipelineMetrics.CLASSIFIER_NATIVE_FEATURES));
            assertEquals(1, DecorationPipelineMetrics.get(DecorationPipelineMetrics.CLASSIFIER_PARTIAL_FEATURES));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> tiers = (java.util.Map<String, Object>) WorldgenProfileMetrics.snapshot().get("tiers");
            assertEquals(1L, tiers.get(WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES.name()));
            assertEquals(1L, tiers.get(WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE.name()));
        } finally {
            DecorationPipelineMetrics.reset();
            WorldgenProfileMetrics.reset();
            DecorationPipelineMetrics.setEnabled(previous);
            WorldgenProfileMetrics.setEnabled(previousProfileMetrics);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PlacedFeature placed(Feature<?> feature, FeatureConfiguration config) {
        ConfiguredFeature configuredFeature = new ConfiguredFeature((Feature) feature, config);
        return new PlacedFeature(Holder.direct(configuredFeature), List.of());
    }
}
