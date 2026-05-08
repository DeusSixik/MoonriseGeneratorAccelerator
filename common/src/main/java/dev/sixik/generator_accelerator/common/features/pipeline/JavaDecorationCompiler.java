package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.features.pipeline.ore.OreTargetCompiler;
import dev.sixik.generator_accelerator.common.features.pipeline.ore.OreTargetPlan;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.CountConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SculkPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.List;

public final class JavaDecorationCompiler {
    private static final int MAX_COMPILED_BRANCH_DEPTH = 6;

    public DecorationPlan compile(Object[][] featuresByStep) {
        if (featuresByStep == null || featuresByStep.length == 0) {
            return DecorationPlan.empty();
        }

        DecorationStepPlan[] steps = new DecorationStepPlan[featuresByStep.length];
        for (int step = 0; step < featuresByStep.length; step++) {
            steps[step] = this.compileStep(step, featuresByStep[step]);
        }
        return new DecorationPlan(steps);
    }

    public DecorationStepPlan compileStep(int step, Object[] features) {
        if (features == null || features.length == 0) {
            return DecorationStepPlan.empty(step);
        }

        int featureCount = features.length;
        int featureMaskWords = (featureCount + Long.SIZE - 1) >>> 6;
        DecorationKernelPlan[] kernels = new DecorationKernelPlan[featureCount];
        PlacedFeature[] fallbackFeatures = new PlacedFeature[featureCount];
        int[] fallbackFeatureIndices = new int[featureCount];
        long[] descriptorFeatureMask = featureMaskWords == 0 ? null : new long[featureMaskWords];
        int fallbackCount = 0;

        for (int featureIndex = 0; featureIndex < featureCount; featureIndex++) {
            Object value = features[featureIndex];
            if (!(value instanceof PlacedFeature feature)) {
                continue;
            }

            DecorationKernelPlan kernel = this.compileFeature(feature, featureIndex);
            kernels[featureIndex] = kernel;
            if (kernel != null && needsDescriptors(kernel)) {
                descriptorFeatureMask[featureIndex >>> 6] |= 1L << (featureIndex & 63);
            }
            if (kernel.hasFallbackFeature()) {
                fallbackFeatures[fallbackCount] = feature;
                fallbackFeatureIndices[fallbackCount] = featureIndex;
                fallbackCount++;
            }
        }

        if (fallbackCount != featureCount) {
            fallbackFeatures = copyFeatures(fallbackFeatures, fallbackCount);
            fallbackFeatureIndices = copyInts(fallbackFeatureIndices, fallbackCount);
        }

        return new DecorationStepPlan(step, featureCount, kernels, fallbackFeatures, fallbackFeatureIndices, descriptorFeatureMask);
    }

    protected DecorationKernelPlan compileFeature(PlacedFeature feature, int originalFeatureIndex) {
        return this.compileFeature(feature, originalFeatureIndex, MAX_COMPILED_BRANCH_DEPTH);
    }

    private DecorationKernelPlan compileFeature(PlacedFeature feature, int originalFeatureIndex, int remainingBranchDepth) {
        DecorationPlacementProgram placementProgram = DecorationPlacementProgram.compile(feature);
        DecorationKernelKind kind = classify(feature);
        DecorationPlacementProgram nestedPlacementProgram = null;
        ConfiguredFeature<?, ?> nestedConfiguredFeature = null;
        OreTargetPlan oreTargetPlan = null;
        SelectorPlan selectorPlan = null;
        if (kind == DecorationKernelKind.NATIVE_ORE || kind == DecorationKernelKind.NATIVE_SCATTERED_ORE) {
            ConfiguredFeature<?, ?> configuredFeature = feature.feature().value();
            if (configuredFeature != null
                    && configuredFeature.config() instanceof OreConfiguration oreConfiguration
                    && canUseNativeOre(oreConfiguration)) {
                oreTargetPlan = OreTargetCompiler.compile(oreConfiguration);
            } else {
                kind = DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED;
            }
        }
        if (kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE) {
            PlacedFeature nestedPlacedFeature = nestedPlacedFeature(feature);
            nestedConfiguredFeature = nestedConfiguredFeature(nestedPlacedFeature);
            nestedPlacementProgram = DecorationPlacementProgram.compile(nestedPlacedFeature);
            if (nestedPlacementProgram.hasVanillaModifier() || nestedPlacementProgram.hasBiomeFilter()) {
                kind = DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
                nestedConfiguredFeature = null;
                nestedPlacementProgram = null;
            }
        }
        if (kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR) {
            PlacedFeature nestedPlacedFeature = nestedPlacedFeature(feature);
            nestedConfiguredFeature = nestedConfiguredFeature(nestedPlacedFeature);
            nestedPlacementProgram = DecorationPlacementProgram.compile(nestedPlacedFeature);
            if (nestedPlacementProgram.hasVanillaModifier() || nestedPlacementProgram.hasBiomeFilter()) {
                kind = DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
                nestedConfiguredFeature = null;
                nestedPlacementProgram = null;
            } else {
                selectorPlan = this.compileSelectorPlan(nestedConfiguredFeature, true, remainingBranchDepth);
                if (selectorPlan == null) {
                    kind = DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
                    nestedConfiguredFeature = null;
                    nestedPlacementProgram = null;
                }
            }
        }
        if (kind == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE && remainingBranchDepth > 0) {
            ConfiguredFeature<?, ?> configuredFeature = feature.feature().value();
            selectorPlan = this.compileSelectorPlan(configuredFeature, false, remainingBranchDepth);
            if (selectorPlan == null) {
                kind = DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
            }
        } else if (kind == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE) {
            kind = DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
        }
        if (kind.isNativeKernel()) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_KERNELS_COMPILED);
        } else {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.PARTIAL_NATIVE_KERNELS_COMPILED);
        }
        if (placementProgram.hasVanillaModifier()) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SLOW_PATH_OBJECT_ALLOCATING_CALLS);
        }
        if (kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE) {
            return DecorationKernelPlan.nativeRandomPatchSimple(
                    feature,
                    originalFeatureIndex,
                    placementProgram,
                    nestedConfiguredFeature,
                    nestedPlacementProgram
            );
        }
        if (kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR) {
            return DecorationKernelPlan.nativeRandomPatchSelector(
                    feature,
                    originalFeatureIndex,
                    placementProgram,
                    nestedConfiguredFeature,
                    nestedPlacementProgram,
                    selectorPlan
            );
        }
        if (kind == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE) {
            return DecorationKernelPlan.nativeSelectorSimple(feature, originalFeatureIndex, placementProgram, selectorPlan);
        }
        if (kind == DecorationKernelKind.NATIVE_SCATTERED_ORE) {
            return DecorationKernelPlan.nativeScatteredOre(feature, originalFeatureIndex, placementProgram, oreTargetPlan);
        }
        if (kind == DecorationKernelKind.NATIVE_ORE) {
            return DecorationKernelPlan.nativeOre(feature, originalFeatureIndex, placementProgram, oreTargetPlan);
        }
        if (kind.isNativeKernel()) {
            return DecorationKernelPlan.nativeClassified(kind, feature, originalFeatureIndex, placementProgram);
        }
        if (kind.isPartialNative()) {
            return DecorationKernelPlan.partialNativeClassified(kind, feature, originalFeatureIndex, placementProgram);
        }
        return DecorationKernelPlan.partialNativePlacement(feature, originalFeatureIndex, placementProgram);
    }

    private static DecorationKernelKind classify(PlacedFeature placedFeature) {
        if (placedFeature == null) {
            return DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
        }
        return classifyConfigured(placedFeature.feature().value());
    }

    private static DecorationKernelKind classifyConfigured(ConfiguredFeature<?, ?> configuredFeature) {
        if (configuredFeature == null) {
            return DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
        }

        Feature<?> feature = configuredFeature.feature();
        FeatureConfiguration config = configuredFeature.config();
        if (feature == Feature.SCATTERED_ORE) {
            return DecorationKernelKind.NATIVE_SCATTERED_ORE;
        }
        if (feature == Feature.ORE) {
            return DecorationKernelKind.NATIVE_ORE;
        }
        if (feature == Feature.SIMPLE_BLOCK) return DecorationKernelKind.NATIVE_SIMPLE_BLOCK;
        if (feature == Feature.SPRING) {
            return config instanceof SpringConfiguration
                    ? DecorationKernelKind.NATIVE_SPRING
                    : DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED;
        }
        if (feature == Feature.SEAGRASS || feature == Feature.KELP) {
            return DecorationKernelKind.NATIVE_PLANT_WATER;
        }
        if (feature == Feature.SEA_PICKLE) {
            return config instanceof CountConfiguration
                    ? DecorationKernelKind.NATIVE_PLANT_WATER
                    : DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED;
        }
        if (feature == Feature.DISK) {
            return DecorationKernelKind.NATIVE_DISK;
        }
        if (feature == Feature.BLOCK_COLUMN) {
            return DecorationKernelKind.NATIVE_BLOCK_COLUMN;
        }
        if (feature == Feature.FREEZE_TOP_LAYER) {
            return DecorationKernelKind.NATIVE_SNOW_FREEZE;
        }
        if (feature == Feature.SCULK_PATCH) {
            return config instanceof SculkPatchConfiguration
                    ? DecorationKernelKind.NATIVE_SCULK_PATCH
                    : DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
        }
        if (feature == Feature.GEODE
                || feature == Feature.MONSTER_ROOM
                || feature == Feature.MULTIFACE_GROWTH
                || feature == Feature.DRIPSTONE_CLUSTER
                || feature == Feature.LARGE_DRIPSTONE
                || feature == Feature.POINTED_DRIPSTONE) {
            return DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED;
        }
        if (feature == Feature.TREE) {
            return DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
        }
        if (isRandomPatchFeature(feature) && config instanceof RandomPatchConfiguration randomPatch) {
            return classifyRandomPatch(randomPatch);
        }
        if (isSelectorFeature(feature)) {
            return DecorationKernelKind.NATIVE_SELECTOR_SIMPLE;
        }
        return DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
    }

    private static DecorationKernelKind classifyRandomPatch(RandomPatchConfiguration randomPatch) {
        PlacedFeature nestedPlaced = randomPatch.feature().value();
        if (nestedPlaced == null) return DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
        ConfiguredFeature<?, ?> nestedConfigured = nestedPlaced.feature().value();
        if (nestedConfigured == null) return DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;

        Feature<?> nestedFeature = nestedConfigured.feature();
        if (nestedFeature == Feature.SIMPLE_BLOCK) {
            return DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE;
        }
        if (isSelectorFeature(nestedFeature)) {
            return DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR;
        }
        return DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT;
    }

    private SelectorPlan compileSelectorPlan(
            ConfiguredFeature<?, ?> configuredFeature,
            boolean requireNativeSimpleBlockBranches,
            int remainingBranchDepth
    ) {
        if (configuredFeature == null) {
            return null;
        }
        if (remainingBranchDepth <= 0) {
            return null;
        }

        Feature<?> feature = configuredFeature.feature();
        FeatureConfiguration config = configuredFeature.config();
        if (feature == Feature.RANDOM_SELECTOR && config instanceof RandomFeatureConfiguration randomFeature) {
            return this.compileRandomSelectorPlan(randomFeature, requireNativeSimpleBlockBranches, remainingBranchDepth);
        }
        if (feature == Feature.RANDOM_BOOLEAN_SELECTOR && config instanceof RandomBooleanFeatureConfiguration randomBoolean) {
            return this.compileRandomBooleanSelectorPlan(randomBoolean, requireNativeSimpleBlockBranches, remainingBranchDepth);
        }
        if (feature == Feature.SIMPLE_RANDOM_SELECTOR && config instanceof SimpleRandomFeatureConfiguration simpleRandom) {
            return this.compileSimpleRandomSelectorPlan(simpleRandom, requireNativeSimpleBlockBranches, remainingBranchDepth);
        }
        return null;
    }

    private SelectorPlan compileRandomSelectorPlan(
            RandomFeatureConfiguration config,
            boolean requireNativeSimpleBlockBranches,
            int remainingBranchDepth
    ) {
        List<WeightedPlacedFeature> entries = config.features;
        int branchCount = entries.size() + 1;
        DecorationKernelPlan[] branchKernels = new DecorationKernelPlan[branchCount];
        float[] chances = new float[entries.size()];

        for (int i = 0; i < entries.size(); i++) {
            WeightedPlacedFeature entry = entries.get(i);
            DecorationKernelPlan branchKernel = this.compileSelectorBranch(
                    entry.feature.value(),
                    i,
                    requireNativeSimpleBlockBranches,
                    remainingBranchDepth - 1
            );
            if (branchKernel == null) {
                return null;
            }
            branchKernels[i] = branchKernel;
            chances[i] = entry.chance;
        }
        DecorationKernelPlan defaultKernel = this.compileSelectorBranch(
                config.defaultFeature.value(),
                branchCount - 1,
                requireNativeSimpleBlockBranches,
                remainingBranchDepth - 1
        );
        if (defaultKernel == null) {
            return null;
        }
        branchKernels[branchCount - 1] = defaultKernel;
        return new SelectorPlan(SelectorPlan.MODE_RANDOM_FEATURE, branchKernels, chances);
    }

    private SelectorPlan compileRandomBooleanSelectorPlan(
            RandomBooleanFeatureConfiguration config,
            boolean requireNativeSimpleBlockBranches,
            int remainingBranchDepth
    ) {
        DecorationKernelPlan[] branchKernels = new DecorationKernelPlan[2];
        branchKernels[0] = this.compileSelectorBranch(
                config.featureTrue.value(),
                0,
                requireNativeSimpleBlockBranches,
                remainingBranchDepth - 1
        );
        if (branchKernels[0] == null) {
            return null;
        }
        branchKernels[1] = this.compileSelectorBranch(
                config.featureFalse.value(),
                1,
                requireNativeSimpleBlockBranches,
                remainingBranchDepth - 1
        );
        if (branchKernels[1] == null) {
            return null;
        }
        return new SelectorPlan(SelectorPlan.MODE_RANDOM_BOOLEAN, branchKernels, null);
    }

    private SelectorPlan compileSimpleRandomSelectorPlan(
            SimpleRandomFeatureConfiguration config,
            boolean requireNativeSimpleBlockBranches,
            int remainingBranchDepth
    ) {
        HolderSet<PlacedFeature> features = config.features;
        int branchCount = features.size();
        if (branchCount <= 0) {
            return null;
        }
        DecorationKernelPlan[] branchKernels = new DecorationKernelPlan[branchCount];
        for (int i = 0; i < branchCount; i++) {
            Holder<PlacedFeature> holder = features.get(i);
            DecorationKernelPlan branchKernel = this.compileSelectorBranch(
                    holder.value(),
                    i,
                    requireNativeSimpleBlockBranches,
                    remainingBranchDepth - 1
            );
            if (branchKernel == null) {
                return null;
            }
            branchKernels[i] = branchKernel;
        }
        return new SelectorPlan(SelectorPlan.MODE_SIMPLE_RANDOM, branchKernels, null);
    }

    private DecorationKernelPlan compileSelectorBranch(
            PlacedFeature placedFeature,
            int index,
            boolean requireNativeSimpleBlockBranches,
            int remainingBranchDepth
    ) {
        if (placedFeature == null) {
            return null;
        }
        ConfiguredFeature<?, ?> configuredFeature = placedFeature.feature().value();
        if (configuredFeature == null) {
            return null;
        }
        DecorationPlacementProgram placementProgram = DecorationPlacementProgram.compile(placedFeature);
        if (requireNativeSimpleBlockBranches
                && (placementProgram.hasVanillaModifier() || placementProgram.hasBiomeFilter())) {
            return null;
        }
        Feature<?> branchFeature = configuredFeature.feature();
        if (branchFeature == Feature.SIMPLE_BLOCK
                && !placementProgram.hasVanillaModifier()
                && !placementProgram.hasBiomeFilter()) {
            return DecorationKernelPlan.nativeClassified(
                    DecorationKernelKind.NATIVE_SIMPLE_BLOCK,
                    placedFeature,
                    index,
                    placementProgram
            );
        }
        if (requireNativeSimpleBlockBranches || remainingBranchDepth <= 0) {
            return null;
        }
        if (isRandomPatchFeature(branchFeature) && configuredFeature.config() instanceof RandomPatchConfiguration randomPatch) {
            DecorationKernelKind kind = classifyRandomPatch(randomPatch);
            if (kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE) {
                return this.compileRandomPatchSimpleBranch(placedFeature, index, placementProgram);
            }
            if (kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR) {
                return this.compileRandomPatchSelectorBranch(placedFeature, index, placementProgram);
            }
        }
        return this.compileFeature(placedFeature, index, remainingBranchDepth);
    }

    private DecorationKernelPlan compileRandomPatchSimpleBranch(
            PlacedFeature placedFeature,
            int index,
            DecorationPlacementProgram placementProgram
    ) {
        PlacedFeature nestedPlacedFeature = nestedPlacedFeature(placedFeature);
        ConfiguredFeature<?, ?> nestedConfiguredFeature = nestedConfiguredFeature(nestedPlacedFeature);
        if (nestedConfiguredFeature == null || nestedConfiguredFeature.feature() != Feature.SIMPLE_BLOCK) {
            return null;
        }
        DecorationPlacementProgram nestedPlacementProgram = DecorationPlacementProgram.compile(nestedPlacedFeature);
        if (nestedPlacementProgram.hasVanillaModifier() || nestedPlacementProgram.hasBiomeFilter()) {
            return null;
        }
        return DecorationKernelPlan.nativeRandomPatchSimple(
                placedFeature,
                index,
                placementProgram,
                nestedConfiguredFeature,
                nestedPlacementProgram
        );
    }

    private DecorationKernelPlan compileRandomPatchSelectorBranch(
            PlacedFeature placedFeature,
            int index,
            DecorationPlacementProgram placementProgram
    ) {
        PlacedFeature nestedPlacedFeature = nestedPlacedFeature(placedFeature);
        ConfiguredFeature<?, ?> nestedConfiguredFeature = nestedConfiguredFeature(nestedPlacedFeature);
        if (nestedConfiguredFeature == null || !isSelectorFeature(nestedConfiguredFeature.feature())) {
            return null;
        }
        DecorationPlacementProgram nestedPlacementProgram = DecorationPlacementProgram.compile(nestedPlacedFeature);
        if (nestedPlacementProgram.hasVanillaModifier() || nestedPlacementProgram.hasBiomeFilter()) {
            return null;
        }
        SelectorPlan selectorPlan = this.compileSelectorPlan(nestedConfiguredFeature, true, MAX_COMPILED_BRANCH_DEPTH);
        if (selectorPlan == null) {
            return null;
        }
        return DecorationKernelPlan.nativeRandomPatchSelector(
                placedFeature,
                index,
                placementProgram,
                nestedConfiguredFeature,
                nestedPlacementProgram,
                selectorPlan
        );
    }

    private static boolean isSelectorFeature(Feature<?> feature) {
        return feature == Feature.RANDOM_SELECTOR
                || feature == Feature.RANDOM_BOOLEAN_SELECTOR
                || feature == Feature.SIMPLE_RANDOM_SELECTOR;
    }

    private static boolean canUseNativeOre(OreConfiguration config) {
        if (config.targetStates.isEmpty()) {
            return false;
        }
        for (int i = 0; i < config.targetStates.size(); i++) {
            if (!isRawOreWriteSafe(config.targetStates.get(i).state)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRawOreWriteSafe(net.minecraft.world.level.block.state.BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static PlacedFeature nestedPlacedFeature(PlacedFeature feature) {
        ConfiguredFeature<?, ?> configuredFeature = feature.feature().value();
        if (configuredFeature == null || !(configuredFeature.config() instanceof RandomPatchConfiguration randomPatch)) {
            return null;
        }
        return randomPatch.feature().value();
    }

    private static ConfiguredFeature<?, ?> nestedConfiguredFeature(PlacedFeature feature) {
        if (feature == null) {
            return null;
        }
        return feature.feature().value();
    }

    private static boolean isRandomPatchFeature(Feature<?> feature) {
        return feature == Feature.RANDOM_PATCH
                || feature == Feature.FLOWER
                || feature == Feature.NO_BONEMEAL_FLOWER;
    }

    private static PlacedFeature[] copyFeatures(PlacedFeature[] source, int size) {
        PlacedFeature[] copy = new PlacedFeature[size];
        for (int i = 0; i < size; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static int[] copyInts(int[] source, int size) {
        int[] copy = new int[size];
        for (int i = 0; i < size; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static boolean needsDescriptors(DecorationKernelPlan kernel) {
        DecorationKernelKind kind = kernel.kind();
        if (kind == DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED
                || kind == DecorationKernelKind.NATIVE_ORE
                || kind == DecorationKernelKind.NATIVE_SCATTERED_ORE
                || kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE
                || kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR
                || kind == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE
                || kind == DecorationKernelKind.NATIVE_SIMPLE_BLOCK
                || kind == DecorationKernelKind.NATIVE_DISK
                || kind == DecorationKernelKind.NATIVE_BLOCK_COLUMN
                || kind == DecorationKernelKind.NATIVE_PLANT_WATER
                || kind == DecorationKernelKind.NATIVE_SPRING
                || kind == DecorationKernelKind.NATIVE_TREE) {
            return true;
        }

        Holder<ConfiguredFeature<?, ?>> configuredHolder = kernel.configuredFeature();
        if (configuredHolder == null) {
            return false;
        }
        ConfiguredFeature<?, ?> configuredFeature = configuredHolder.value();
        if (configuredFeature == null) {
            return false;
        }

        Feature<?> feature = configuredFeature.feature();
        return feature == Feature.ORE
                || feature == Feature.SCATTERED_ORE
                || feature == Feature.KELP
                || feature == Feature.SEAGRASS
                || feature == Feature.SEA_PICKLE
                || feature == Feature.WATERLOGGED_VEGETATION_PATCH
                || feature == Feature.SIMPLE_BLOCK
                || isRandomPatchFeature(feature)
                || feature == Feature.VEGETATION_PATCH;
    }
}
