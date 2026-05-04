package dev.sixik.generator_accelerator.mixins.common_mixin.features.place.tree_decorator;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.CocoaDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.*;

@Mixin(CocoaDecorator.class)
public abstract class MixinCocoaDecorator extends TreeDecorator {

    @Shadow
    @Final
    private float probability;

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final Direction[] BTS$HORIZONTAL_DIRS =
            Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);

    /**
     * @author Sixik
     * @reason Fixed crash when the trunk list was empty, removed Stream API
     */
    @Overwrite
    public void place(Context context) {
        RandomSource randomSource = context.random();

        if (randomSource.nextFloat() >= this.probability) {
            return;
        }

        ObjectArrayList<BlockPos> logs = context.logs();
        if (logs.isEmpty()) {
            return;
        }

        int baseY = logs.get(0).getY();
        BlockPos.MutableBlockPos mutPos = BTS$MUTABLE_POS.get();

        Object[] logsArray = logs.elements();
        int size = logs.size();

        for (int i = 0; i < size; i++) {
            BlockPos pos = (BlockPos) logsArray[i];

            if (pos.getY() - baseY <= 2) {
                int px = pos.getX();
                int py = pos.getY();
                int pz = pos.getZ();

                for (int d = 0; d < BTS$HORIZONTAL_DIRS.length; d++) {
                    if (randomSource.nextFloat() <= 0.25F) {
                        Direction direction = BTS$HORIZONTAL_DIRS[d];
                        Direction opposite = direction.getOpposite();

                        mutPos.set(px + opposite.getStepX(), py, pz + opposite.getStepZ());

                        if (context.isAir(mutPos)) {
                            BlockState cocoaState = Blocks.COCOA.defaultBlockState()
                                    .setValue(CocoaBlock.AGE, randomSource.nextInt(3))
                                    .setValue(CocoaBlock.FACING, direction);

                            context.setBlock(mutPos, cocoaState);
                        }
                    }
                }
            }
        }
    }
}
