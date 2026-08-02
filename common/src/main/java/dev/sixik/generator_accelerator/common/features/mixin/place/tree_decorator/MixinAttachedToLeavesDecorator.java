package dev.sixik.generator_accelerator.common.features.mixin.place.tree_decorator;

import dev.sixik.generator_accelerator_native_raw.structures.NativeBlockPosBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.AttachedToLeavesDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$SHUFFLE_POS_A =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$SHUFFLE_POS_B =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<LongOpenHashSet> BTS$EXCLUSION_SET =
            ThreadLocal.withInitial(() -> new LongOpenHashSet(256));

    /**
     * @author Sixik
     * @reason Replaced {@link HashSet} with a primitive long set, removed shuffledCopy, replaced betweenClosed with nested int loops.
     */
    @Overwrite
    public void place(TreeDecorator.Context context) {
        final List<BlockPos> leaves = context.leaves();
        if (leaves.isEmpty()) return;

        final RandomSource randomSource = context.random();
        final LongOpenHashSet exclusionSet = BTS$EXCLUSION_SET.get();
        exclusionSet.clear();
        final BlockPos.MutableBlockPos mutPos = BTS$MUTABLE_POS.get();
        try (NativeBlockPosBuffer shuffledLeaves = new NativeBlockPosBuffer(leaves.size())) {
            for (int i = 0, size = leaves.size(); i < size; i++) {
                shuffledLeaves.add(leaves.get(i));
            }
            ga$shuffleLeaves(shuffledLeaves, randomSource);

            final int size = shuffledLeaves.size();
            final int dirsSize = this.directions.size();
            final BlockPos.MutableBlockPos leafPos = BTS$SHUFFLE_POS_A.get();

            for (int i = 0; i < size; i++) {
                shuffledLeaves.get(i, leafPos);

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
    }

    @Unique
    private boolean bts$hasRequiredEmptyBlocks(TreeDecorator.Context context, BlockPos startPos, Direction direction, BlockPos.MutableBlockPos mutPos) {
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

    @Unique
    private static void ga$shuffleLeaves(NativeBlockPosBuffer leaves, RandomSource random) {
        BlockPos.MutableBlockPos left = BTS$SHUFFLE_POS_A.get();
        BlockPos.MutableBlockPos right = BTS$SHUFFLE_POS_B.get();
        for (int i = leaves.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            leaves.swap(i, j, left, right);
        }
    }
}
