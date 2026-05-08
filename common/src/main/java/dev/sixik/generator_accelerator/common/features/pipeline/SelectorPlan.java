package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;

final class SelectorPlan {
    static final int MODE_RANDOM_FEATURE = 0;
    static final int MODE_RANDOM_BOOLEAN = 1;
    static final int MODE_SIMPLE_RANDOM = 2;
    static final int FAST_BRANCH_NONE = 0;
    static final int FAST_BRANCH_SIMPLE_BLOCK = 1;
    static final int FAST_BRANCH_RANDOM_PATCH_SIMPLE = 2;
    static final int FAST_BRANCH_RANDOM_PATCH_SELECTOR = 3;
    static final int FAST_BRANCH_SELECTOR = 4;

    private final int mode;
    private final DecorationKernelPlan[] branchKernels;
    private final ConfiguredFeature<?, ?>[] branchConfiguredFeatures;
    private final DecorationPlacementProgram[] branchPlacementPrograms;
    private final int[] branchDescriptorGates;
    private final int[] branchFastModes;
    private final SimpleBlockConfiguration[] branchSimpleBlockConfigurations;
    private final RandomPatchConfiguration[] branchRandomPatchConfigurations;
    private final float[] branchChances;
    private final boolean allBranchesFastSimpleBlock;
    private final boolean allBranchesFastSimpleFamily;

    SelectorPlan(
            int mode,
            DecorationKernelPlan[] branchKernels,
            float[] branchChances
    ) {
        this.mode = mode;
        this.branchKernels = branchKernels;
        this.branchConfiguredFeatures = new ConfiguredFeature[branchKernels.length];
        this.branchPlacementPrograms = new DecorationPlacementProgram[branchKernels.length];
        this.branchDescriptorGates = new int[branchKernels.length];
        this.branchFastModes = new int[branchKernels.length];
        this.branchSimpleBlockConfigurations = new SimpleBlockConfiguration[branchKernels.length];
        this.branchRandomPatchConfigurations = new RandomPatchConfiguration[branchKernels.length];
        for (int i = 0; i < branchKernels.length; i++) {
            DecorationKernelPlan kernel = branchKernels[i];
            if (kernel == null) {
                continue;
            }
            DecorationPlacementProgram placementProgram = kernel.placementProgram();
            this.branchPlacementPrograms[i] = placementProgram;
            ConfiguredFeature<?, ?> configuredFeature = kernel.configuredFeature() == null ? null : kernel.configuredFeature().value();
            this.branchConfiguredFeatures[i] = configuredFeature;
            if (configuredFeature != null) {
                this.branchDescriptorGates[i] = DecorationPlacementProgram.descriptorGateForFeature(configuredFeature.feature());
            }
            if (placementProgram == null || configuredFeature == null) {
                continue;
            }

            FeatureConfiguration configuration = configuredFeature.config();
            if (configuredFeature.feature() == Feature.SIMPLE_BLOCK && configuration instanceof SimpleBlockConfiguration simpleBlockConfiguration) {
                this.branchFastModes[i] = FAST_BRANCH_SIMPLE_BLOCK;
                this.branchSimpleBlockConfigurations[i] = simpleBlockConfiguration;
            } else if (kernel.kind() == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE
                    && configuration instanceof RandomPatchConfiguration randomPatchConfiguration) {
                this.branchFastModes[i] = FAST_BRANCH_RANDOM_PATCH_SIMPLE;
                this.branchRandomPatchConfigurations[i] = randomPatchConfiguration;
            } else if (kernel.kind() == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR
                    && configuration instanceof RandomPatchConfiguration randomPatchConfiguration
                    && kernel.selectorPlan() != null
                    && kernel.selectorPlan().allBranchesFastSimpleFamily()) {
                this.branchFastModes[i] = FAST_BRANCH_RANDOM_PATCH_SELECTOR;
                this.branchRandomPatchConfigurations[i] = randomPatchConfiguration;
            } else if (kernel.kind() == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE
                    && kernel.selectorPlan() != null
                    && kernel.selectorPlan().allBranchesFastSimpleFamily()) {
                this.branchFastModes[i] = FAST_BRANCH_SELECTOR;
            }
        }
        boolean allSimple = branchKernels.length > 0;
        boolean allSimpleFamily = branchKernels.length > 0;
        for (int i = 0; i < branchKernels.length; i++) {
            if (this.branchFastModes[i] != FAST_BRANCH_SIMPLE_BLOCK || this.branchSimpleBlockConfigurations[i] == null) {
                allSimple = false;
            }
            int fastMode = this.branchFastModes[i];
            if (fastMode != FAST_BRANCH_SIMPLE_BLOCK
                    && fastMode != FAST_BRANCH_RANDOM_PATCH_SIMPLE
                    && fastMode != FAST_BRANCH_RANDOM_PATCH_SELECTOR
                    && fastMode != FAST_BRANCH_SELECTOR) {
                allSimpleFamily = false;
            }
        }
        this.allBranchesFastSimpleBlock = allSimple;
        this.allBranchesFastSimpleFamily = allSimpleFamily;
        this.branchChances = branchChances;
    }

    int mode() {
        return this.mode;
    }

    DecorationKernelPlan[] branchKernels() {
        return this.branchKernels;
    }

    ConfiguredFeature<?, ?>[] branchConfiguredFeatures() {
        return this.branchConfiguredFeatures;
    }

    DecorationPlacementProgram[] branchPlacementPrograms() {
        return this.branchPlacementPrograms;
    }

    int[] branchDescriptorGates() {
        return this.branchDescriptorGates;
    }

    int[] branchFastModes() {
        return this.branchFastModes;
    }

    SimpleBlockConfiguration[] branchSimpleBlockConfigurations() {
        return this.branchSimpleBlockConfigurations;
    }

    RandomPatchConfiguration[] branchRandomPatchConfigurations() {
        return this.branchRandomPatchConfigurations;
    }

    boolean allBranchesFastSimpleBlock() {
        return this.allBranchesFastSimpleBlock;
    }

    boolean allBranchesFastSimpleFamily() {
        return this.allBranchesFastSimpleFamily;
    }

    float[] branchChances() {
        return this.branchChances;
    }
}
