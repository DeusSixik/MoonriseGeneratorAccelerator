package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;

public interface GA$EnvironmentScanPlacementAccess {
    BlockPredicate ga$allowedSearchCondition();

    BlockPredicate ga$targetCondition();

    Direction ga$directionOfSearch();

    int ga$maxSteps();
}
