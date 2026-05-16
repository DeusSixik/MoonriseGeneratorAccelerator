package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.tree.ChunkTreeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(ChunkTreeHelper.class)
public abstract class DynamicTrees$ChunkTreeHelperMixin {
    /**
     * @author Sixik
     * @reason Dynamic Trees only needs the loaded-chunk predicate here; avoid
     * AABB block-state streams, lambdas, spliterators and BlockPos iteration.
     */
    @Overwrite(remap = false)
    public static boolean canCheckSurroundings(LevelAccessor level, AABB box) {
        return level.hasChunksAt(
                Mth.floor(box.minX),
                Mth.floor(box.minY),
                Mth.floor(box.minZ),
                Mth.floor(box.maxX),
                Mth.floor(box.maxY),
                Mth.floor(box.maxZ)
        );
    }

    /**
     * @author Sixik
     * @reason Avoid two BlockPos allocations and an AABB allocation per leaf
     * hydration check while preserving Dynamic Trees' full-block AABB bounds.
     */
    @Overwrite(remap = false)
    public static boolean canCheckSurroundings(LevelAccessor level, BlockPos pos, int radius) {
        return level.hasChunksAt(
                pos.getX() - radius,
                pos.getY() - radius,
                pos.getZ() - radius,
                pos.getX() + radius + 1,
                pos.getY() + radius + 1,
                pos.getZ() + radius + 1
        );
    }
}
