package dev.sixik.generator_accelerator.common.features.mixin.place.tree_decorator;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.feature.treedecorators.LeaveVineDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.*;

@Mixin(LeaveVineDecorator.class)
public abstract class MixinLeaveVineDecorator extends TreeDecorator {

    @Shadow
    @Final
    private float probability;
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Faster version
     */
    @Overwrite
    public void place(Context context) {
        ObjectArrayList<BlockPos> leaves = context.leaves();
        if (leaves.isEmpty()) return;

        RandomSource random = context.random();
        BlockPos.MutableBlockPos mutPos = BTS$MUTABLE_POS.get();

        Object[] leavesArray = leaves.elements();
        int size = leaves.size();

        for (int idx = 0; idx < size; idx++) {
            BlockPos pos = (BlockPos) leavesArray[idx];
            int px = pos.getX();
            int py = pos.getY();
            int pz = pos.getZ();

            if (random.nextFloat() < this.probability) {
                if (context.isAir(mutPos.set(px - 1, py, pz))) {
                    bts$addHangingVine(px - 1, py, pz, VineBlock.EAST, context, mutPos);
                }
            }

            if (random.nextFloat() < this.probability) {
                if (context.isAir(mutPos.set(px + 1, py, pz))) {
                    bts$addHangingVine(px + 1, py, pz, VineBlock.WEST, context, mutPos);
                }
            }

            if (random.nextFloat() < this.probability) {
                if (context.isAir(mutPos.set(px, py, pz - 1))) {
                    bts$addHangingVine(px, py, pz - 1, VineBlock.SOUTH, context, mutPos);
                }
            }

            if (random.nextFloat() < this.probability) {
                if (context.isAir(mutPos.set(px, py, pz + 1))) {
                    bts$addHangingVine(px, py, pz + 1, VineBlock.NORTH, context, mutPos);
                }
            }
        }
    }

    @Unique
    private static void bts$addHangingVine(int startX, int startY, int startZ, BooleanProperty property, Context context, BlockPos.MutableBlockPos mutPos) {
        mutPos.set(startX, startY, startZ);
        context.placeVine(mutPos, property);

        int vinesLeft = 4;
        int currentY = startY - 1;

        while (vinesLeft > 0) {
            mutPos.setY(currentY);
            if (!context.isAir(mutPos)) {
                break;
            }
            context.placeVine(mutPos, property);
            currentY--;
            vinesLeft--;
        }
    }
}
