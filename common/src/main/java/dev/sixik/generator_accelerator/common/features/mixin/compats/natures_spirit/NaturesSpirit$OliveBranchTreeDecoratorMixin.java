package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritBlocks;
import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritFeatureUtil;
import net.hibiscus.naturespirit.blocks.OliveBranchBlock;
import net.hibiscus.naturespirit.world.tree_decorator.OliveBranchTreeDecorator;
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

@Mixin(value = OliveBranchTreeDecorator.class, remap = false)
public abstract class NaturesSpirit$OliveBranchTreeDecoratorMixin {
    @Shadow
    @Final
    private float probability;

    /**
     * @author Sixik
     * @reason Remove stream/lambda traversal, Direction.values() cloning and BlockPos.offset allocations.
     */
    @Overwrite(remap = false)
    public void place(TreeDecorator.Context context) {
        RandomSource random = context.random();
        List<BlockPos> logs = context.logs();
        if (logs.isEmpty()) {
            return;
        }

        int minY = logs.get(0).getY() + 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < logs.size(); ++i) {
            BlockPos log = logs.get(i);
            if (log.getY() <= minY) {
                continue;
            }

            for (Direction direction : NaturesSpiritFeatureUtil.DIRECTIONS) {
                if (random.nextFloat() >= this.probability) {
                    continue;
                }
                Direction facing = direction.getOpposite();
                pos.set(
                        log.getX() + facing.getStepX(),
                        log.getY() + facing.getStepY(),
                        log.getZ() + facing.getStepZ()
                );
                if (context.isAir(pos)) {
                    BlockState state = NaturesSpiritBlocks.oliveBranch().defaultBlockState()
                            .setValue(OliveBranchBlock.FACING, facing)
                            .setValue(OliveBranchBlock.AGE, random.nextInt(4));
                    context.setBlock(pos, state);
                }
            }
        }
    }
}



