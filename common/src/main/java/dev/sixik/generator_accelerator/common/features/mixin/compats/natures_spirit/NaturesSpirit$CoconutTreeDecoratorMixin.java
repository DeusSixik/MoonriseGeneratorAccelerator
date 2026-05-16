package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritBlocks;
import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritFeatureUtil;
import net.hibiscus.naturespirit.blocks.CoconutBlock;
import net.hibiscus.naturespirit.world.tree_decorator.CoconutTreeDecorator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(value = CoconutTreeDecorator.class, remap = false)
public abstract class NaturesSpirit$CoconutTreeDecoratorMixin {
    @Shadow
    @Final
    private float probability;

    /**
     * @author Sixik
     * @reason Replace stream/lambda and per-neighbor BlockPos allocations with a compact loop.
     */
    @Overwrite(remap = false)
    public void place(TreeDecorator.Context context) {
        RandomSource random = context.random();
        if (random.nextFloat() >= this.probability) {
            return;
        }

        List<BlockPos> logs = context.logs();
        if (logs.isEmpty()) {
            return;
        }

        int minY = logs.get(logs.size() - 1).getY() - 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < logs.size(); ++i) {
            BlockPos log = logs.get(i);
            if (log.getY() < minY) {
                continue;
            }
            for (Direction direction : NaturesSpiritFeatureUtil.HORIZONTAL) {
                if (random.nextFloat() > 0.5F) {
                    continue;
                }
                Direction facing = direction.getOpposite();
                pos.set(log.getX() + facing.getStepX(), log.getY(), log.getZ() + facing.getStepZ());
                if (context.isAir(pos)) {
                    BlockState state = NaturesSpiritBlocks.coconut().defaultBlockState().setValue(CoconutBlock.FACING, facing);
                    context.setBlock(pos, state);
                }
            }
        }
    }
}



