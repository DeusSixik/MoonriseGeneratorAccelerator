package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PhaseBSelectorCompilerTest {

    private final JavaDecorationCompiler compiler = new JavaDecorationCompiler();

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrapHelper.ensureBootstrapped();
    }

    @Test
    void randomBooleanSelectorCompilesIntoFastSimpleBlockBranches() {
        PlacedFeature grass = placedSimpleBlock(Blocks.SHORT_GRASS);
        PlacedFeature flower = placedSimpleBlock(Blocks.DANDELION);
        PlacedFeature selector = placed(
                Feature.RANDOM_BOOLEAN_SELECTOR,
                new RandomBooleanFeatureConfiguration(Holder.direct(grass), Holder.direct(flower))
        );

        DecorationStepPlan stepPlan = this.compiler.compileStep(0, new Object[]{selector});
        DecorationKernelPlan kernel = stepPlan.kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_SELECTOR_SIMPLE, kernel.kind());
        assertNotNull(kernel.selectorPlan());
        assertEquals(true, kernel.selectorPlan().allBranchesFastSimpleBlock());
        assertEquals(true, kernel.selectorPlan().allBranchesFastSimpleFamily());
        assertEquals(SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK, kernel.selectorPlan().branchFastModes()[0]);
        assertEquals(SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK, kernel.selectorPlan().branchFastModes()[1]);
    }

    @Test
    void selectorKeepsNativeRootWhenOneBranchNeedsPartialFallback() {
        PlacedFeature grass = placedSimpleBlock(Blocks.SHORT_GRASS);
        PlacedFeature tree = placed(Feature.TREE, FeatureConfiguration.NONE);
        PlacedFeature selector = placed(
                Feature.RANDOM_BOOLEAN_SELECTOR,
                new RandomBooleanFeatureConfiguration(Holder.direct(grass), Holder.direct(tree))
        );

        DecorationKernelPlan kernel = this.compiler.compileStep(0, new Object[]{selector}).kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_SELECTOR_SIMPLE, kernel.kind());
        assertNotNull(kernel.selectorPlan());
        assertEquals(false, kernel.selectorPlan().allBranchesFastSimpleBlock());
        assertEquals(false, kernel.selectorPlan().allBranchesFastSimpleFamily());
        assertEquals(DecorationKernelKind.NATIVE_SIMPLE_BLOCK, kernel.selectorPlan().branchKernels()[0].kind());
        assertEquals(DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT, kernel.selectorPlan().branchKernels()[1].kind());
        assertEquals(SelectorPlan.FAST_BRANCH_NONE, kernel.selectorPlan().branchFastModes()[1]);
        assertEquals(-1, kernel.selectorPlan().branchKernels()[1].selectorFallbackMetricCounter());
    }

    @Test
    void selectorWithRandomPatchSimpleBranchesUsesFusedSimpleFamily() {
        PlacedFeature grassPatch = placedRandomPatchSimple(Blocks.SHORT_GRASS);
        PlacedFeature flowerPatch = placedRandomPatchSimple(Blocks.DANDELION);
        PlacedFeature selector = placed(
                Feature.RANDOM_BOOLEAN_SELECTOR,
                new RandomBooleanFeatureConfiguration(Holder.direct(grassPatch), Holder.direct(flowerPatch))
        );

        DecorationKernelPlan kernel = this.compiler.compileStep(0, new Object[]{selector}).kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_SELECTOR_SIMPLE, kernel.kind());
        assertNotNull(kernel.selectorPlan());
        assertEquals(false, kernel.selectorPlan().allBranchesFastSimpleBlock());
        assertEquals(true, kernel.selectorPlan().allBranchesFastSimpleFamily());
        assertEquals(SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SIMPLE, kernel.selectorPlan().branchFastModes()[0]);
        assertEquals(SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SIMPLE, kernel.selectorPlan().branchFastModes()[1]);
    }

    @Test
    void selectorSimpleBranchesKeepFusedModeWithPlacementPrograms() {
        PlacedFeature grass = placedSimpleBlock(Blocks.SHORT_GRASS, InSquarePlacement.spread());
        PlacedFeature flower = placedSimpleBlock(Blocks.DANDELION, InSquarePlacement.spread());
        PlacedFeature selector = placed(
                Feature.RANDOM_BOOLEAN_SELECTOR,
                new RandomBooleanFeatureConfiguration(Holder.direct(grass), Holder.direct(flower))
        );

        DecorationKernelPlan kernel = this.compiler.compileStep(0, new Object[]{selector}).kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_SELECTOR_SIMPLE, kernel.kind());
        assertNotNull(kernel.selectorPlan());
        assertEquals(true, kernel.selectorPlan().allBranchesFastSimpleFamily());
        assertEquals(SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK, kernel.selectorPlan().branchFastModes()[0]);
        assertEquals(SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK, kernel.selectorPlan().branchFastModes()[1]);
        assertEquals(false, kernel.selectorPlan().branchPlacementPrograms()[0].isIdentity());
    }

    @Test
    void randomPatchSimpleKeepsNativeWithNestedBiomeFilter() {
        PlacedFeature grass = placedSimpleBlock(Blocks.SHORT_GRASS, BiomeFilter.biome());
        PlacedFeature grassPatch = placed(
                Feature.RANDOM_PATCH,
                new RandomPatchConfiguration(8, 3, 3, Holder.direct(grass))
        );

        DecorationKernelPlan kernel = this.compiler.compileStep(0, new Object[]{grassPatch}).kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE, kernel.kind());
        assertNotNull(kernel.nestedPlacementProgram());
        assertEquals(true, kernel.nestedPlacementProgram().hasBiomeFilter());
    }

    @Test
    void randomPatchSelectorKeepsNativeWithNestedSelectorBiomeFilter() {
        PlacedFeature grass = placedSimpleBlock(Blocks.SHORT_GRASS);
        PlacedFeature flower = placedSimpleBlock(Blocks.DANDELION);
        PlacedFeature selector = placed(
                Feature.RANDOM_BOOLEAN_SELECTOR,
                new RandomBooleanFeatureConfiguration(Holder.direct(grass), Holder.direct(flower)),
                BiomeFilter.biome()
        );
        PlacedFeature randomPatchSelector = placed(
                Feature.RANDOM_PATCH,
                new RandomPatchConfiguration(8, 3, 3, Holder.direct(selector))
        );

        DecorationKernelPlan kernel = this.compiler.compileStep(0, new Object[]{randomPatchSelector}).kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR, kernel.kind());
        assertNotNull(kernel.selectorPlan());
        assertNotNull(kernel.nestedPlacementProgram());
        assertEquals(true, kernel.nestedPlacementProgram().hasBiomeFilter());
    }

    @Test
    void randomPatchSelectorBranchesKeepFusedModeWithPlacementPrograms() {
        PlacedFeature grass = placedSimpleBlock(Blocks.SHORT_GRASS, InSquarePlacement.spread());
        PlacedFeature flower = placedSimpleBlock(Blocks.DANDELION, InSquarePlacement.spread());
        PlacedFeature selector = placed(
                Feature.RANDOM_BOOLEAN_SELECTOR,
                new RandomBooleanFeatureConfiguration(Holder.direct(grass), Holder.direct(flower))
        );
        PlacedFeature randomPatchSelector = placed(
                Feature.RANDOM_PATCH,
                new RandomPatchConfiguration(8, 3, 3, Holder.direct(selector))
        );

        DecorationKernelPlan kernel = this.compiler.compileStep(0, new Object[]{randomPatchSelector}).kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR, kernel.kind());
        assertNotNull(kernel.selectorPlan());
        assertEquals(true, kernel.selectorPlan().allBranchesFastSimpleFamily());
        assertEquals(SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK, kernel.selectorPlan().branchFastModes()[0]);
        assertEquals(SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK, kernel.selectorPlan().branchFastModes()[1]);
        assertEquals(false, kernel.selectorPlan().branchPlacementPrograms()[0].isIdentity());
    }

    @Test
    void randomPatchSelectorKeepsNativeOuterKernelWhenSelectorContainsPartialBranch() {
        PlacedFeature grass = placedSimpleBlock(Blocks.SHORT_GRASS);
        PlacedFeature tree = placed(Feature.TREE, FeatureConfiguration.NONE);
        PlacedFeature selector = placed(
                Feature.RANDOM_BOOLEAN_SELECTOR,
                new RandomBooleanFeatureConfiguration(Holder.direct(grass), Holder.direct(tree))
        );
        PlacedFeature randomPatchSelector = placed(
                Feature.RANDOM_PATCH,
                new RandomPatchConfiguration(8, 3, 3, Holder.direct(selector))
        );

        DecorationKernelPlan kernel = this.compiler.compileStep(0, new Object[]{randomPatchSelector}).kernelForFeatureIndex(0);

        assertNotNull(kernel);
        assertEquals(DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR, kernel.kind());
        assertNotNull(kernel.selectorPlan());
        assertEquals(DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT, kernel.selectorPlan().branchKernels()[1].kind());
        assertEquals(SelectorPlan.FAST_BRANCH_NONE, kernel.selectorPlan().branchFastModes()[1]);
        assertEquals(-1, kernel.selectorPlan().branchKernels()[1].selectorFallbackMetricCounter());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PlacedFeature placed(Feature<?> feature, FeatureConfiguration config) {
        return placed(feature, config, new PlacementModifier[0]);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PlacedFeature placed(Feature<?> feature, FeatureConfiguration config, PlacementModifier... placement) {
        ConfiguredFeature configuredFeature = new ConfiguredFeature((Feature) feature, config);
        return new PlacedFeature(Holder.direct(configuredFeature), List.of(placement));
    }

    private static PlacedFeature placedSimpleBlock(net.minecraft.world.level.block.Block block) {
        return placedSimpleBlock(block, new net.minecraft.world.level.levelgen.placement.PlacementModifier[0]);
    }

    private static PlacedFeature placedSimpleBlock(
            net.minecraft.world.level.block.Block block,
            PlacementModifier... placement
    ) {
        ConfiguredFeature configuredFeature = new ConfiguredFeature(
                Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(block))
        );
        return new PlacedFeature(Holder.direct(configuredFeature), List.of(placement));
    }

    private static PlacedFeature placedRandomPatchSimple(net.minecraft.world.level.block.Block block) {
        return placed(
                Feature.RANDOM_PATCH,
                new RandomPatchConfiguration(8, 3, 3, Holder.direct(placedSimpleBlock(block)))
        );
    }
}
