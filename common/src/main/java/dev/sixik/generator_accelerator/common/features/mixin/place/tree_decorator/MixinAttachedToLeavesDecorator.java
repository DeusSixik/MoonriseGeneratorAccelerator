package dev.sixik.generator_accelerator.common.features.mixin.place.tree_decorator;

import dev.sixik.generator_accelerator.api.utils.FastMathUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.AttachedToLeavesDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.*;

import java.util.HashSet;
import java.util.List;

@Mixin(AttachedToLeavesDecorator.class)
public abstract class MixinAttachedToLeavesDecorator extends TreeDecorator {

    @Shadow
    @Final
    protected List<Direction> directions;
    @Shadow
    @Final
    protected int exclusionRadiusXZ;
    @Shadow
    @Final
    protected int exclusionRadiusY;
    @Shadow
    @Final
    protected float probability;
    @Shadow
    @Final
    protected BlockStateProvider blockProvider;
    @Shadow
    @Final
    protected int requiredEmptyBlocks;
    @Unique
    private static final ThreadLocal<ObjectArrayList<BlockPos>> BTS$SHUFFLE_BUFFER =
            ThreadLocal.withInitial(ObjectArrayList::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<LongOpenHashSet> BTS$EXCLUSION_SET =
            ThreadLocal.withInitial(LongOpenHashSet::new);

    /**
     * @author Sixik
     * @reason Replaced {@link HashSet} with {@link LongOpenHashSet}, removed shuffledCopy, replaced betweenClosed with nested int loops.
     */
    @Overwrite
    public void place(Context context) {
        final ObjectArrayList<BlockPos> leaves = context.leaves();
        if (leaves.isEmpty()) return;

        final RandomSource randomSource = context.random();

        final ObjectArrayList<BlockPos> shuffledLeaves = BTS$SHUFFLE_BUFFER.get();
        shuffledLeaves.clear();
        shuffledLeaves.addAll(leaves);
        FastMathUtils.shuffle(shuffledLeaves, randomSource);

        final LongOpenHashSet exclusionSet = BTS$EXCLUSION_SET.get();
        exclusionSet.clear();

        final BlockPos.MutableBlockPos mutPos = BTS$MUTABLE_POS.get();

        final Object[] leavesArray = shuffledLeaves.elements();
        final int size = shuffledLeaves.size();

        final int dirsSize = this.directions.size();

        for (int i = 0; i < size; i++) {
            final BlockPos leafPos = (BlockPos) leavesArray[i];

            final Direction direction = this.directions.get(randomSource.nextInt(dirsSize));

            final int targetX = leafPos.getX() + direction.getStepX();
            final int targetY = leafPos.getY() + direction.getStepY();
            final int targetZ = leafPos.getZ() + direction.getStepZ();
            final long targetPacked = BlockPos.asLong(targetX, targetY, targetZ);

            if (!exclusionSet.contains(targetPacked)
                    && randomSource.nextFloat() < this.probability
                    && bts$hasRequiredEmptyBlocks(context, leafPos, direction, mutPos)) {

                final int minX = targetX - this.exclusionRadiusXZ;
                final int maxX = targetX + this.exclusionRadiusXZ;
                final int minY = targetY - this.exclusionRadiusY;
                final int maxY = targetY + this.exclusionRadiusY;
                final int minZ = targetZ - this.exclusionRadiusXZ;
                final int maxZ = targetZ + this.exclusionRadiusXZ;

                for (int x = minX; x <= maxX; ++x) {
                    for (int y = minY; y <= maxY; ++y) {
                        for (int z = minZ; z <= maxZ; ++z) {
                            exclusionSet.add(BlockPos.asLong(x, y, z));
                        }
                    }
                }

                mutPos.set(targetX, targetY, targetZ);
                context.setBlock(mutPos, this.blockProvider.getState(randomSource, mutPos));
            }
        }
    }

    @Unique
    private boolean bts$hasRequiredEmptyBlocks(Context context, BlockPos startPos, Direction direction, BlockPos.MutableBlockPos mutPos) {
        final int x = startPos.getX();
        final int y = startPos.getY();
        final int z = startPos.getZ();

        final int stepX = direction.getStepX();
        final int stepY = direction.getStepY();
        final int stepZ = direction.getStepZ();

        for (int i = 1; i <= this.requiredEmptyBlocks; ++i) {
            mutPos.set(x + stepX * i, y + stepY * i, z + stepZ * i);
            if (!context.isAir(mutPos)) {
                return false;
            }
        }

        return true;
    }
}
