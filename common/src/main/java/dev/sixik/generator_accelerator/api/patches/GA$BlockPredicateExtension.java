package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;

public interface GA$BlockPredicateExtension {
    boolean ga$testRaw(WorldGenLevel level, int x, int y, int z, BlockPos.MutableBlockPos scratch);

    static boolean testRaw(
            BlockPredicate predicate,
            WorldGenLevel level,
            int x,
            int y,
            int z,
            BlockPos.MutableBlockPos scratch
    ) {
        if (predicate instanceof GA$BlockPredicateExtension fastPredicate) {
            return fastPredicate.ga$testRaw(level, x, y, z, scratch);
        }
        return predicate.test(level, scratch.set(x, y, z));
    }
}
