package dev.sixik.generator_accelerator.common.features.vm;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.List;

@Deprecated(forRemoval = false)
public final class FeatureProgramCache {
    @Deprecated(forRemoval = false)
    private FeatureProgramCache() {
    }

    @Deprecated(forRemoval = false)
    public static FeatureProgram getOrCompile(List<PlacementModifier> placement, Holder<ConfiguredFeature<?, ?>> feature) {
        return FeaturePlacementCompiler.compile(placement, feature);
    }

    @Deprecated(forRemoval = false)
    public static void clear() {
        // Programs are stored on PlacedFeature instances; datapack reload creates new instances.
    }
}
