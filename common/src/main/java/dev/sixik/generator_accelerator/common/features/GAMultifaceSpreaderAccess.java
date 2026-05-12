package dev.sixik.generator_accelerator.common.features;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

public interface GAMultifaceSpreaderAccess {
    boolean ga$spreadFromFaceTowardRandomDirectionNoResult(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            RandomSource random,
            boolean markForPostprocessing
    );
}
