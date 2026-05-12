package dev.sixik.generator_accelerator.common.features.mixin.features;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "net.minecraft.world.level.block.SculkVeinBlock$SculkVeinSpreaderConfig", priority = 999)
public abstract class MixinSculkVeinSpreaderConfig {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SCULK_SIDE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SCULK_SUPPORT_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Avoid two BlockPos.relative allocations in every sculk-vein spread replacement probe.
     */
    @Overwrite
    public boolean stateCanBeReplaced(
            BlockGetter level,
            BlockPos sourcePos,
            BlockPos spreadPos,
            Direction direction,
            BlockState state
    ) {
        BlockPos.MutableBlockPos sidePos = GA$SCULK_SIDE_POS.get().setWithOffset(spreadPos, direction);
        BlockState sideState = level.getBlockState(sidePos);
        if (sideState.is(Blocks.SCULK) || sideState.is(Blocks.SCULK_CATALYST) || sideState.is(Blocks.MOVING_PISTON)) {
            return false;
        }

        if (sourcePos.distManhattan(spreadPos) == 2) {
            BlockPos.MutableBlockPos supportPos = GA$SCULK_SUPPORT_POS.get().setWithOffset(sourcePos, direction.getOpposite());
            if (level.getBlockState(supportPos).isFaceSturdy(level, supportPos, direction)) {
                return false;
            }
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty() && !fluidState.is(Fluids.WATER)) {
            return false;
        }
        if (state.is(BlockTags.FIRE)) {
            return false;
        }
        return state.canBeReplaced()
                || state.isAir()
                || state.is(Blocks.SCULK_VEIN)
                || state.is(Blocks.WATER) && fluidState.isSource();
    }
}
