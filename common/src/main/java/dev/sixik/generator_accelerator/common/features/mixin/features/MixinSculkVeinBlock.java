package dev.sixik.generator_accelerator.common.features.mixin.features;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SculkVeinBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SculkVeinBlock.class, priority = 999)
public abstract class MixinSculkVeinBlock {
    @Unique
    private static final Direction[] GA$DIRECTIONS = {
            Direction.DOWN,
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SUBSTRATE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Avoid BlockPos.relative allocation while the sculk cursor searches for substrate access.
     */
    @Overwrite
    public static boolean hasSubstrateAccess(LevelAccessor level, BlockState state, BlockPos pos) {
        if (!state.is(Blocks.SCULK_VEIN)) {
            return false;
        }

        BlockPos.MutableBlockPos sidePos = GA$SUBSTRATE_POS.get();
        for (int i = 0; i < GA$DIRECTIONS.length; i++) {
            Direction direction = GA$DIRECTIONS[i];
            if (!MultifaceBlock.hasFace(state, direction)) {
                continue;
            }
            sidePos.setWithOffset(pos, direction);
            if (level.getBlockState(sidePos).is(BlockTags.SCULK_REPLACEABLE)) {
                return true;
            }
        }
        return false;
    }
}
