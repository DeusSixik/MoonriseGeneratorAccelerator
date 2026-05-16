package dev.sixik.generator_accelerator.common.features.mixin.place.tree_decorator;

import dev.sixik.generator_accelerator_native_raw.structures.NativeBlockPosBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BeehiveDecorator.class)
public abstract class MixinBeehiveDecorator extends TreeDecorator {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS_BUFFER =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> CANDIDATE_POS_BUFFER =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> SHUFFLE_POS_A =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> SHUFFLE_POS_B =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    @Final
    private float probability;

    @Shadow
    @Final
    private static Direction[] SPAWN_DIRECTIONS;

    @Shadow
    @Final
    private static Direction WORLDGEN_FACING;

    /**
     * @author Sixik
     * @reason Replace temporary candidate BlockPos allocations with an off-heap buffer while preserving shuffle parity.
     */
    @Overwrite
    public void place(TreeDecorator.Context context) {
        ObjectArrayList<BlockPos> leaves = context.leaves();
        ObjectArrayList<BlockPos> logs = context.logs();

        if (logs.isEmpty()) {
            return;
        }

        RandomSource random = context.random();
        if (random.nextFloat() >= this.probability) {
            return;
        }

        int targetY;
        if (!leaves.isEmpty()) {
            targetY = Math.max(leaves.get(0).getY() - 1, logs.get(0).getY() + 1);
        } else {
            targetY = Math.min(logs.get(0).getY() + 1 + random.nextInt(3), logs.getLast().getY());
        }

        Object[] logsArray = logs.elements();
        int logsSize = logs.size();
        int expectedCandidates = Math.max(1, logsSize * SPAWN_DIRECTIONS.length);

        try (NativeBlockPosBuffer candidates = new NativeBlockPosBuffer(expectedCandidates)) {
            BlockPos.MutableBlockPos candidatePos = CANDIDATE_POS_BUFFER.get();
            for (int idx = 0; idx < logsSize; idx++) {
                BlockPos logPos = (BlockPos) logsArray[idx];
                if (logPos.getY() != targetY) {
                    continue;
                }

                int x = logPos.getX();
                int y = logPos.getY();
                int z = logPos.getZ();
                for (Direction dir : SPAWN_DIRECTIONS) {
                    candidatePos.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                    candidates.add(candidatePos);
                }
            }

            int candidatesSize = candidates.size();
            if (candidatesSize == 0) {
                return;
            }
            ga$shuffleCandidates(candidates, random);

            BlockPos.MutableBlockPos selectedPos = CANDIDATE_POS_BUFFER.get();
            BlockPos.MutableBlockPos checkPos = MUTABLE_POS_BUFFER.get();
            boolean found = false;

            for (int idx = 0; idx < candidatesSize; idx++) {
                candidates.get(idx, selectedPos);
                if (!context.isAir(selectedPos)) {
                    continue;
                }

                checkPos.setWithOffset(selectedPos, WORLDGEN_FACING);
                if (context.isAir(checkPos)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                return;
            }

            context.setBlock(selectedPos, Blocks.BEE_NEST.defaultBlockState().setValue(BeehiveBlock.FACING, WORLDGEN_FACING));
            context.level().getBlockEntity(selectedPos, BlockEntityType.BEEHIVE).ifPresent(storeBee -> {
                int beeCount = 2 + random.nextInt(2);
                for (int k = 0; k < beeCount; k++) {
                    storeBee.storeBee(BeehiveBlockEntity.Occupant.create(random.nextInt(599)));
                }
            });
        }
    }

    @Unique
    private static void ga$shuffleCandidates(NativeBlockPosBuffer candidates, RandomSource random) {
        BlockPos.MutableBlockPos left = SHUFFLE_POS_A.get();
        BlockPos.MutableBlockPos right = SHUFFLE_POS_B.get();
        for (int i = candidates.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            candidates.swap(i, j, left, right);
        }
    }
}
