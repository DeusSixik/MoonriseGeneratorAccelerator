package dev.sixik.generator_accelerator.common.features.mixin.place.tree_decorator;

import dev.sixik.generator_accelerator.api.utils.FastMathUtils;
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
import org.spongepowered.asm.mixin.*;

@Mixin(BeehiveDecorator.class)
public abstract class MixinBeehiveDecorator extends TreeDecorator {

    @Unique
    private static final ThreadLocal<ObjectArrayList<BlockPos>> CANDIDATES_BUFFER =
            ThreadLocal.withInitial(ObjectArrayList::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS_BUFFER =
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
     * @reason Fast Version
     */
    @Overwrite
    public void place(TreeDecorator.Context context) {
        ObjectArrayList<BlockPos> leaves = context.leaves();
        ObjectArrayList<BlockPos> logs = context.logs();

        if (logs.isEmpty()) return;

        RandomSource randomsource = context.random();
        if (randomsource.nextFloat() >= this.probability) return;

        /*
            Вычисляем целевой уровень Y без изменений (Seed Parity)
         */
        int targetY;
        if (!leaves.isEmpty()) {
            targetY = Math.max(leaves.get(0).getY() - 1, logs.get(0).getY() + 1);
        } else {
            targetY = Math.min(logs.get(0).getY() + 1 + randomsource.nextInt(3), logs.getLast().getY());
        }

        /*
            Собираем кандидатов
         */
        ObjectArrayList<BlockPos> candidates = CANDIDATES_BUFFER.get();
        candidates.clear();

        Object[] logsArray = logs.elements();
        int logsSize = logs.size();

        for (int idx = 0; idx < logsSize; idx++) {
            BlockPos logPos = (BlockPos) logsArray[idx];
            if (logPos.getY() == targetY) {
                for (Direction dir : SPAWN_DIRECTIONS) {
                    candidates.add(logPos.relative(dir));
                }
            }
        }

        if (candidates.isEmpty()) return;
        FastMathUtils.shuffle(candidates, randomsource);

        /*
            Ищем первую подходящую позицию
         */
        BlockPos selectedPos = null;
        BlockPos.MutableBlockPos checkPos = MUTABLE_POS_BUFFER.get();

        Object[] candidatesArray = candidates.elements();
        int candidatesSize = candidates.size();

        for (int idx = 0; idx < candidatesSize; idx++) {
            BlockPos pos = (BlockPos) candidatesArray[idx];

            if (context.isAir(pos)) {
                checkPos.setWithOffset(pos, WORLDGEN_FACING);

                if (context.isAir(checkPos)) {
                    selectedPos = pos;
                    break;
                }
            }
        }

        /*
            Размещаем улей
         */
        if (selectedPos != null) {
            context.setBlock(selectedPos, Blocks.BEE_NEST.defaultBlockState().setValue(BeehiveBlock.FACING, WORLDGEN_FACING));

            context.level().getBlockEntity(selectedPos, BlockEntityType.BEEHIVE).ifPresent(storeBee -> {
                int beeCount = 2 + randomsource.nextInt(2);
                for (int k = 0; k < beeCount; k++) {
                    storeBee.storeBee(BeehiveBlockEntity.Occupant.create(randomsource.nextInt(599)));
                }
            });
        }
    }
}
