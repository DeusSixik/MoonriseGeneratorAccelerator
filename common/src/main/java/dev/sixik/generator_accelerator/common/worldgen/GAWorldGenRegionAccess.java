package dev.sixik.generator_accelerator.common.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

public interface GAWorldGenRegionAccess {
    boolean ga$canWriteWithoutLogging(BlockPos pos);

    static boolean canWriteWithoutLogging(LevelAccessor level, BlockPos pos) {
        if (level instanceof GAWorldGenRegionAccess access) {
            return access.ga$canWriteWithoutLogging(pos);
        }
        return true;
    }
}
