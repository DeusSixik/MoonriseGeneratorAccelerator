package dev.sixik.generator_accelerator.common.features.vm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

@Deprecated(forRemoval = false)
public final class FeaturePlacementCompat {
    @Deprecated(forRemoval = false)
    private FeaturePlacementCompat() {
    }

    @Deprecated(forRemoval = false)
    public static boolean enabled() {
        return false;
    }

    @Deprecated(forRemoval = false)
    public static boolean beforePlace(Holder<ConfiguredFeature<?, ?>> feature, PlacementContext context, RandomSource random, BlockPos.MutableBlockPos pos) {
        return false;
    }
}
