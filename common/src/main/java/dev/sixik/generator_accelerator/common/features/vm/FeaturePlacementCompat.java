package dev.sixik.generator_accelerator.common.features.vm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

public final class FeaturePlacementCompat {
    private FeaturePlacementCompat() {
    }

    public static boolean enabled() {
        return false;
    }

    public static boolean beforePlace(Holder<ConfiguredFeature<?, ?>> feature, PlacementContext context, RandomSource random, BlockPos.MutableBlockPos pos) {
        return false;
    }
}
