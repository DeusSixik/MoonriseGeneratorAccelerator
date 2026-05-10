package dev.sixik.generator_accelerator.common.features.pipeline;

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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PlacedFeature placed(Feature<?> feature, FeatureConfiguration config) {
        ConfiguredFeature configuredFeature = new ConfiguredFeature((Feature) feature, config);
        return new PlacedFeature(Holder.direct(configuredFeature), List.of());
    }
}
