package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.cell.Cell;
import com.dtteam.dynamictrees.api.treedata.TreePart;
import com.dtteam.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.dtteam.dynamictrees.block.leaves.LeavesProperties;
import com.dtteam.dynamictrees.tree.TreeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DynamicLeavesBlock.class, remap = false)
public abstract class DynamicTrees$DynamicLeavesBlockMixin {
    @Unique
    private static final Direction[] GA$DIRECTIONS = Direction.values();

    @Unique
    private static final ThreadLocal<Cell[]> GA$HYDRATION_CELLS =
            ThreadLocal.withInitial(() -> new Cell[6]);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$HYDRATION_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$UPDATE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow(remap = false)
    public abstract LeavesProperties getLeavesProperties();

    @Shadow(remap = false)
    public abstract int updateHydro(LevelAccessor accessor, BlockPos pos, BlockState state, boolean worldGen);

    @Shadow(remap = false)
    public abstract boolean removeIfInvalid(BlockState state, LevelAccessor level, BlockPos pos, RandomSource rand);

    @Shadow(remap = false)
    public abstract int growLeavesIfLocationIsSuitable(
            LevelAccessor level,
            LeavesProperties leavesProp,
            BlockPos pos,
            Integer hydro
    );

    /**
     * @author Sixik
     * @reason Keep Dynamic Trees leaf aging semantics while eliminating the
     * Direction.values clone and six short-lived neighbor BlockPos objects.
     */
    @Overwrite(remap = false)
    public int updateLeaves(LevelAccessor level, BlockPos pos, BlockState state, RandomSource rand, boolean worldGen) {
        int newHydro = this.updateHydro(level, pos, state, worldGen);
        if (newHydro == 0) {
            return 0;
        }
        if (!worldGen && this.removeIfInvalid(state, level, pos, rand)) {
            return 0;
        }

        LeavesProperties leavesProperties = this.getLeavesProperties();
        BlockPos.MutableBlockPos sidePos = GA$UPDATE_POS.get();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        for (int i = 0; i < GA$DIRECTIONS.length; i++) {
            Direction dir = GA$DIRECTIONS[i];
            if (newHydro <= 1 && rand.nextInt(4) != 0) {
                continue;
            }
            sidePos.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            this.growLeavesIfLocationIsSuitable(level, leavesProperties, sidePos, null);
        }
        return newHydro;
    }

    /**
     * @author Sixik
     * @reason Hydration checks dominate Dynamic Trees decoration in dense
     * modpacks; reuse the six-cell buffer and mutable neighbor coordinate.
     */
    @Overwrite(remap = false)
    public int getHydrationLevelFromNeighbors(LevelAccessor level, BlockPos pos, LeavesProperties leavesProperties) {
        Cell[] cells = GA$HYDRATION_CELLS.get();
        BlockPos.MutableBlockPos deltaPos = GA$HYDRATION_POS.get();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        for (int i = 0; i < GA$DIRECTIONS.length; i++) {
            Direction dir = GA$DIRECTIONS[i];
            deltaPos.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            BlockState state = level.getBlockState(deltaPos);
            TreePart part = TreeHelper.getTreePart(state);
            cells[dir.ordinal()] = part.getHydrationCell(level, deltaPos, state, dir, leavesProperties);
        }
        return leavesProperties.getCellKit().getCellSolver().solve(cells);
    }

    @Redirect(
            method = {"updateAllLeaves", "branchOut"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"),
            remap = false
    )
    private Direction[] ga$cachedDirections() {
        return GA$DIRECTIONS;
    }
}
