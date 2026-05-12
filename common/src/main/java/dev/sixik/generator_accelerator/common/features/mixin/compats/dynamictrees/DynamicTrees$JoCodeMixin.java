package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.voxmap.SimpleVoxmap;
import com.dtteam.dynamictrees.block.leaves.LeavesProperties;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.worldgen.JoCode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = JoCode.class, remap = false)
public abstract class DynamicTrees$JoCodeMixin {
    @Unique
    private static final Direction[] GA$DIRECTIONS = Direction.values();

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Smothering scans the whole leaf voxmap; use primitive voxmap
     * accessors and BlockPos.ZERO instead of allocating a BlockPos per voxel.
     */
    @Overwrite(remap = false)
    protected void smother(SimpleVoxmap leafMap, LeavesProperties leavesProperties) {
        int smotherMax = leavesProperties.getSmotherLeavesMax();
        if (smotherMax == 0) {
            return;
        }

        BlockPos saveCenter = leafMap.getCenter();
        leafMap.setCenter(BlockPos.ZERO);

        int startY = leafMap.getLenY() - 1;
        while (startY >= 0 && !leafMap.isYTouched(startY)) {
            startY--;
        }

        for (int iz = 0, lenZ = leafMap.getLenZ(); iz < lenZ; iz++) {
            for (int ix = 0, lenX = leafMap.getLenX(); ix < lenX; ix++) {
                int count = 0;
                for (int iy = startY; iy >= 0; iy--) {
                    byte value = leafMap.getVoxel(ix, iy, iz);
                    if (value == 0) {
                        count = 0;
                        continue;
                    }
                    if ((value & 0xF) != 0) {
                        if (++count > smotherMax) {
                            leafMap.setVoxel(ix, iy, iz, (byte) 0);
                        }
                        continue;
                    }
                    if ((value & 0x10) != 0) {
                        count++;
                        leafMap.setVoxel(ix, iy + 1, iz, (byte) 4);
                    }
                }
            }
        }
        leafMap.setCenter(saveCenter);
    }

    /**
     * @author Sixik
     * @reason Avoid Direction.values and BlockPos.relative allocation in the
     * careful-generation branch scan.
     */
    @Overwrite(remap = false)
    protected boolean isClearOfNearbyBranches(LevelAccessor level, BlockPos pos, Direction except) {
        BlockPos.MutableBlockPos sidePos = GA$MUTABLE_POS.get();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        for (int i = 0; i < GA$DIRECTIONS.length; i++) {
            Direction dir = GA$DIRECTIONS[i];
            if (dir == except) {
                continue;
            }
            sidePos.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            if (TreeHelper.getBranch(level.getBlockState(sidePos)) != null) {
                return false;
            }
        }
        return true;
    }
}
