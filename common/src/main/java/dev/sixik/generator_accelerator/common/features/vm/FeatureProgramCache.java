package dev.sixik.generator_accelerator.common.features.vm;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.List;

public final class FeatureProgramCache {
    private FeatureProgramCache() {
    }

    public static FeatureProgram getOrCompile(List<PlacementModifier> placement, Holder<ConfiguredFeature<?, ?>> feature) {
        return FeaturePlacementCompiler.compile(placement, feature);
    }

    public static void clear() {
        // Programs are stored on PlacedFeature instances; datapack reload creates new instances.
    }
}
