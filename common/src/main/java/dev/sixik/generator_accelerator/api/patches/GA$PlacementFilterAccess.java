package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

public interface GA$PlacementFilterAccess {
    boolean ga$shouldPlace(PlacementContext context, RandomSource random, BlockPos.MutableBlockPos pos);

    default boolean ga$shouldPlaceRaw(PlacementContext context, RandomSource random, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        return ga$shouldPlace(context, random, scratch.set(x, y, z));
    }
}
