package dev.sixik.generator_accelerator.common.features.mixin.place.tree_decorator;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TrunkVineDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TrunkVineDecorator.class)
public abstract class MixinTrunkVineDecorator extends TreeDecorator {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Fast Version.
     */
    @Overwrite
    public void place(TreeDecorator.Context context) {
        ObjectArrayList<BlockPos> logs = context.logs();
        if (logs.isEmpty()) return;

        RandomSource random = context.random();
        BlockPos.MutableBlockPos mutPos = BTS$MUTABLE_POS.get();

        Object[] logsArray = logs.elements();
        int size = logs.size();

        for (int idx = 0; idx < size; idx++) {
            BlockPos pos = (BlockPos) logsArray[idx];
            int px = pos.getX();
            int py = pos.getY();
            int pz = pos.getZ();

            if (random.nextInt(3) > 0) {
                if (context.isAir(mutPos.set(px - 1, py, pz))) {
                    context.placeVine(mutPos, VineBlock.EAST);
                }
            }

            if (random.nextInt(3) > 0) {
                if (context.isAir(mutPos.set(px + 1, py, pz))) {
                    context.placeVine(mutPos, VineBlock.WEST);
                }
            }

            if (random.nextInt(3) > 0) {
                if (context.isAir(mutPos.set(px, py, pz - 1))) {
                    context.placeVine(mutPos, VineBlock.SOUTH);
                }
            }

            if (random.nextInt(3) > 0) {
                if (context.isAir(mutPos.set(px, py, pz + 1))) {
                    context.placeVine(mutPos, VineBlock.NORTH);
                }
            }
        }
    }
}
