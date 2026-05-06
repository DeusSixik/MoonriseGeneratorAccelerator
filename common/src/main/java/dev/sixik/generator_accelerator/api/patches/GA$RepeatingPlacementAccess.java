package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

public interface GA$RepeatingPlacementAccess {
    int ga$repeatingCount(RandomSource random, BlockPos.MutableBlockPos pos);
}
