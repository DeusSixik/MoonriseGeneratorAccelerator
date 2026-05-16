package dev.sixik.generator_accelerator.common.features.mixin.features;

import dev.sixik.generator_accelerator.common.features.SculkSpreaderCursorScratch;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SculkBehaviour;
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.block.SculkVeinBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = SculkSpreader.ChargeCursor.class, priority = 999)
public abstract class MixinSculkSpreaderChargeCursor {
    @Shadow
    @Final
    private static ObjectArrayList<Vec3i> NON_CORNER_NEIGHBOURS;

    @Unique
    private static final ThreadLocal<ObjectArrayList<Vec3i>> GA$SHUFFLED_NEIGHBOURS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(18));

    @Unique
    private static final ThreadLocal<SculkSpreaderCursorScratch> GA$MOVEMENT_SCRATCH =
            ThreadLocal.withInitial(SculkSpreaderCursorScratch::new);

    /**
     * @author Sixik
     * @reason Preserve Util.shuffle RNG order while reusing the temporary neighbour list.
     */
    @Overwrite
    private static List<Vec3i> getRandomizedNonCornerNeighbourOffsets(RandomSource random) {
        ObjectArrayList<Vec3i> shuffled = GA$SHUFFLED_NEIGHBOURS.get();
        shuffled.clear();
        shuffled.addAll(NON_CORNER_NEIGHBOURS);

        for (int j = shuffled.size(); j > 1; --j) {
            int k = random.nextInt(j);
            shuffled.set(j - 1, shuffled.set(k, shuffled.get(j - 1)));
        }
        return shuffled;
    }

    /**
     * @author Sixik
     * @reason Reuse mutable positions in the sculk worldgen movement probe.
     */
    @Overwrite
    private static BlockPos getValidMovementPos(LevelAccessor level, BlockPos pos, RandomSource random) {
        SculkSpreaderCursorScratch scratch = GA$MOVEMENT_SCRATCH.get();
        BlockPos.MutableBlockPos result = scratch.movementResult;
        BlockPos.MutableBlockPos candidate = scratch.movementCandidate;
        result.set(pos);

        List<Vec3i> offsets = getRandomizedNonCornerNeighbourOffsets(random);
        boolean found = false;
        for (int i = 0, size = offsets.size(); i < size; i++) {
            Vec3i offset = offsets.get(i);
            candidate.setWithOffset(pos, offset);
            BlockState state = level.getBlockState(candidate);
            if (!(state.getBlock() instanceof SculkBehaviour)) {
                continue;
            }
            if (!ga$isMovementUnobstructed(level, pos, candidate, scratch.movementObstructionProbe)) {
                continue;
            }

            result.set(candidate);
            found = true;
            if (SculkVeinBlock.hasSubstrateAccess(level, state, candidate)) {
                break;
            }
        }

        return found ? result : null;
    }

    @Unique
    private static boolean ga$isMovementUnobstructed(
            LevelAccessor level,
            BlockPos from,
            BlockPos to,
            BlockPos.MutableBlockPos probe
    ) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        if (manhattan == 1) {
            return true;
        }

        Direction dirX = dx < 0 ? Direction.WEST : Direction.EAST;
        Direction dirY = dy < 0 ? Direction.DOWN : Direction.UP;
        Direction dirZ = dz < 0 ? Direction.NORTH : Direction.SOUTH;
        if (dx == 0) {
            return ga$isUnobstructed(level, from, dirY, probe) || ga$isUnobstructed(level, from, dirZ, probe);
        }
        if (dy == 0) {
            return ga$isUnobstructed(level, from, dirX, probe) || ga$isUnobstructed(level, from, dirZ, probe);
        }
        return ga$isUnobstructed(level, from, dirX, probe) || ga$isUnobstructed(level, from, dirY, probe);
    }

    @Unique
    private static boolean ga$isUnobstructed(
            LevelAccessor level,
            BlockPos pos,
            Direction direction,
            BlockPos.MutableBlockPos probe
    ) {
        probe.setWithOffset(pos, direction);
        return !level.getBlockState(probe).isFaceSturdy(level, probe, direction.getOpposite());
    }

}
