package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.features.pipeline.ore.OreTargetPlan;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Optional;

public final class DecorationKernelPlan {
    private final DecorationKernelKind kind;
    private final PlacedFeature fallbackFeature;
    private final Optional<PlacedFeature> fallbackFeatureOptional;
    private final Holder<ConfiguredFeature<?, ?>> configuredFeature;
    private final DecorationPlacementProgram placementProgram;
    private final ConfiguredFeature<?, ?> nestedConfiguredFeature;
    private final DecorationPlacementProgram nestedPlacementProgram;
    private final OreTargetPlan oreTargetPlan;
    private final SelectorPlan selectorPlan;
    private final String metricsName;
    private final int originalFeatureIndex;
    private final int orderingGroup;
    private final boolean canBatch;
    private final boolean canRelaxVisibility;

    public DecorationKernelPlan(
            DecorationKernelKind kind,
            PlacedFeature fallbackFeature,
            Holder<ConfiguredFeature<?, ?>> configuredFeature,
            DecorationPlacementProgram placementProgram,
            ConfiguredFeature<?, ?> nestedConfiguredFeature,
            DecorationPlacementProgram nestedPlacementProgram,
            int originalFeatureIndex,
            int orderingGroup,
            boolean canBatch,
            boolean canRelaxVisibility
    ) {
        this(
                kind,
                fallbackFeature,
                configuredFeature,
                placementProgram,
                nestedConfiguredFeature,
                nestedPlacementProgram,
                null,
                null,
                originalFeatureIndex,
                orderingGroup,
                canBatch,
                canRelaxVisibility
        );
    }

    private DecorationKernelPlan(
            DecorationKernelKind kind,
            PlacedFeature fallbackFeature,
            Holder<ConfiguredFeature<?, ?>> configuredFeature,
            DecorationPlacementProgram placementProgram,
            ConfiguredFeature<?, ?> nestedConfiguredFeature,
            DecorationPlacementProgram nestedPlacementProgram,
            OreTargetPlan oreTargetPlan,
            SelectorPlan selectorPlan,
            int originalFeatureIndex,
            int orderingGroup,
            boolean canBatch,
            boolean canRelaxVisibility
    ) {
        this.kind = kind;
        this.fallbackFeature = fallbackFeature;
        this.fallbackFeatureOptional = fallbackFeature == null ? Optional.empty() : Optional.of(fallbackFeature);
        this.configuredFeature = configuredFeature;
        this.placementProgram = placementProgram;
        this.nestedConfiguredFeature = nestedConfiguredFeature;
        this.nestedPlacementProgram = nestedPlacementProgram;
        this.oreTargetPlan = oreTargetPlan;
        this.selectorPlan = selectorPlan;
        this.metricsName = metricsName(kind, configuredFeature);
        this.originalFeatureIndex = originalFeatureIndex;
        this.orderingGroup = orderingGroup;
        this.canBatch = canBatch;
        this.canRelaxVisibility = canRelaxVisibility;
    }

    public static DecorationKernelPlan vanillaFallback(PlacedFeature feature, int originalFeatureIndex) {
        return new DecorationKernelPlan(
                DecorationKernelKind.VANILLA_FALLBACK,
                feature,
                feature.feature(),
                DecorationPlacementProgram.compile(feature),
                null,
                null,
                originalFeatureIndex,
                originalFeatureIndex,
                false,
                false
        );
    }

    public static DecorationKernelPlan partialNativePlacement(PlacedFeature feature, int originalFeatureIndex, DecorationPlacementProgram placementProgram) {
        return partialNativeClassified(DecorationKernelKind.PARTIAL_NATIVE_PLACEMENT, feature, originalFeatureIndex, placementProgram);
    }

    public static DecorationKernelPlan partialNativeClassified(DecorationKernelKind kind, PlacedFeature feature, int originalFeatureIndex, DecorationPlacementProgram placementProgram) {
        return new DecorationKernelPlan(
                kind,
                feature,
                feature.feature(),
                placementProgram,
                null,
                null,
                originalFeatureIndex,
                originalFeatureIndex,
                true,
                true
        );
    }

    public static DecorationKernelPlan nativeClassified(DecorationKernelKind kind, PlacedFeature feature, int originalFeatureIndex, DecorationPlacementProgram placementProgram) {
        return new DecorationKernelPlan(
                kind,
                feature,
                feature.feature(),
                placementProgram,
                null,
                null,
                originalFeatureIndex,
                originalFeatureIndex,
                true,
                true
        );
    }

    public static DecorationKernelPlan nativeScatteredOre(
            PlacedFeature feature,
            int originalFeatureIndex,
            DecorationPlacementProgram placementProgram,
            OreTargetPlan oreTargetPlan
    ) {
        return new DecorationKernelPlan(
                DecorationKernelKind.NATIVE_SCATTERED_ORE,
                feature,
                feature.feature(),
                placementProgram,
                null,
                null,
                oreTargetPlan,
                null,
                originalFeatureIndex,
                originalFeatureIndex,
                true,
                true
        );
    }

    public static DecorationKernelPlan nativeOre(
            PlacedFeature feature,
            int originalFeatureIndex,
            DecorationPlacementProgram placementProgram,
            OreTargetPlan oreTargetPlan
    ) {
        return new DecorationKernelPlan(
                DecorationKernelKind.NATIVE_ORE,
                feature,
                feature.feature(),
                placementProgram,
                null,
                null,
                oreTargetPlan,
                null,
                originalFeatureIndex,
                originalFeatureIndex,
                true,
                true
        );
    }

    public static DecorationKernelPlan nativeRandomPatchSimple(
            PlacedFeature feature,
            int originalFeatureIndex,
            DecorationPlacementProgram placementProgram,
            ConfiguredFeature<?, ?> nestedConfiguredFeature,
            DecorationPlacementProgram nestedPlacementProgram
    ) {
        return new DecorationKernelPlan(
                DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE,
                feature,
                feature.feature(),
                placementProgram,
                nestedConfiguredFeature,
                nestedPlacementProgram,
                null,
                null,
                originalFeatureIndex,
                originalFeatureIndex,
                true,
                true
        );
    }

    public static DecorationKernelPlan nativeRandomPatchSelector(
            PlacedFeature feature,
            int originalFeatureIndex,
            DecorationPlacementProgram placementProgram,
            ConfiguredFeature<?, ?> nestedConfiguredFeature,
            DecorationPlacementProgram nestedPlacementProgram,
            SelectorPlan selectorPlan
    ) {
        return new DecorationKernelPlan(
                DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR,
                feature,
                feature.feature(),
                placementProgram,
                nestedConfiguredFeature,
                nestedPlacementProgram,
                null,
                selectorPlan,
                originalFeatureIndex,
                originalFeatureIndex,
                true,
                true
        );
    }

    public static DecorationKernelPlan nativeSelectorSimple(
            PlacedFeature feature,
            int originalFeatureIndex,
            DecorationPlacementProgram placementProgram,
            SelectorPlan selectorPlan
    ) {
        return new DecorationKernelPlan(
                DecorationKernelKind.NATIVE_SELECTOR_SIMPLE,
                feature,
                feature.feature(),
                placementProgram,
                null,
                null,
                null,
                selectorPlan,
                originalFeatureIndex,
                originalFeatureIndex,
                true,
                true
        );
    }

    public DecorationKernelKind kind() {
        return this.kind;
    }

    public PlacedFeature fallbackFeature() {
        return this.fallbackFeature;
    }

    public Optional<PlacedFeature> fallbackFeatureOptional() {
        return this.fallbackFeatureOptional;
    }

    public Holder<ConfiguredFeature<?, ?>> configuredFeature() {
        return this.configuredFeature;
    }

    public DecorationPlacementProgram placementProgram() {
        return this.placementProgram;
    }

    public ConfiguredFeature<?, ?> nestedConfiguredFeature() {
        return this.nestedConfiguredFeature;
    }

    public DecorationPlacementProgram nestedPlacementProgram() {
        return this.nestedPlacementProgram;
    }

    public OreTargetPlan oreTargetPlan() {
        return this.oreTargetPlan;
    }

    public SelectorPlan selectorPlan() {
        return this.selectorPlan;
    }

    public String metricsName() {
        return this.metricsName;
    }

    public int originalFeatureIndex() {
        return this.originalFeatureIndex;
    }

    public int orderingGroup() {
        return this.orderingGroup;
    }

    public boolean canBatch() {
        return this.canBatch;
    }

    public boolean canRelaxVisibility() {
        return this.canRelaxVisibility;
    }

    public boolean hasFallbackFeature() {
        return this.fallbackFeature != null;
    }

    private static String metricsName(DecorationKernelKind kind, Holder<ConfiguredFeature<?, ?>> configuredFeature) {
        if (configuredFeature == null) {
            return kind.name() + "/unknown";
        }
        ConfiguredFeature<?, ?> configured = configuredFeature.value();
        if (configured == null) {
            return kind.name() + "/unknown";
        }
        Feature<?> feature = configured.feature();
        String featureName = feature == null ? "unknown" : feature.getClass().getSimpleName();
        return kind.name() + "/" + featureName;
    }
}
