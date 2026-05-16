package dev.sixik.generator_accelerator.common.features.compat.natures_spirit;

import net.hibiscus.naturespirit.blocks.BranchingTrunkBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class NaturesSpiritFeatureUtil {
    public static final Direction[] DIRECTIONS = Direction.values();
    public static final Direction[] HORIZONTAL = new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static final ThreadLocal<Direction[]> SHUFFLED_HORIZONTAL = ThreadLocal.withInitial(() -> HORIZONTAL.clone());

    private NaturesSpiritFeatureUtil() {
    }

    public static Direction[] shuffledHorizontal(RandomSource random) {
        Direction[] directions = SHUFFLED_HORIZONTAL.get();
        directions[0] = Direction.NORTH;
        directions[1] = Direction.EAST;
        directions[2] = Direction.SOUTH;
        directions[3] = Direction.WEST;
        for (int i = directions.length - 1; i > 0; --i) {
            int swap = random.nextInt(i + 1);
            Direction value = directions[i];
            directions[i] = directions[swap];
            directions[swap] = value;
        }
        return directions;
    }

    public static boolean isSurroundedByAir(LevelReader world, BlockPos pos, Direction exceptDirection, BlockPos.MutableBlockPos scratch) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        for (Direction direction : HORIZONTAL) {
            if (direction == exceptDirection) {
                continue;
            }
            scratch.set(x + direction.getStepX(), y, z + direction.getStepZ());
            if (!world.isEmptyBlock(scratch)) {
                return false;
            }
        }
        return true;
    }

    public static BlockState branchState(BranchingTrunkBlock block, boolean up, boolean down, boolean north, boolean east, boolean south, boolean west) {
        return block.defaultBlockState()
                .setValue(PipeBlock.UP, up)
                .setValue(PipeBlock.DOWN, down)
                .setValue(PipeBlock.NORTH, north)
                .setValue(PipeBlock.EAST, east)
                .setValue(PipeBlock.SOUTH, south)
                .setValue(PipeBlock.WEST, west);
    }

    public static BlockState verticalState(BranchingTrunkBlock block) {
        return branchState(block, true, true, false, false, false, false);
    }

    public static BlockState capState(BranchingTrunkBlock block) {
        return branchState(block, false, true, false, false, false, false);
    }

    public static BlockState horizontalState(BranchingTrunkBlock block, Direction direction) {
        return connectedState(block, direction, direction.getOpposite());
    }

    public static BlockState connectedState(BranchingTrunkBlock block, Direction first, Direction second) {
        BlockState state = block.defaultBlockState();
        state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(first), true);
        state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(second), true);
        return state;
    }
}
