package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import net.hibiscus.naturespirit.world.tree_decorator.RedwoodBranchTreeDecorator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = RedwoodBranchTreeDecorator.class, remap = false)
public abstract class NaturesSpirit$RedwoodBranchTreeDecoratorMixin {
    @Shadow
    @Final
    private float probability;
    @Shadow
    @Final
    private BlockStateProvider leaf_provider;

    /**
     * @author Sixik
     * @reason Replace stream/lambda and repeated BlockPos offset allocations in redwood branches.
     */
    @Overwrite(remap = false)
    public void place(TreeDecorator.Context context) {
        RandomSource random = context.random();
        List<BlockPos> logs = context.logs();
        List<BlockPos> leaves = context.leaves();
        if (logs.isEmpty() || leaves.isEmpty()) {
            return;
        }

        int minY = logs.get(0).getY() + 2;
        int maxY = leaves.get(0).getY() - 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < logs.size(); ++i) {
            BlockPos log = logs.get(i);
            if (log.getY() >= maxY || log.getY() <= minY || random.nextFloat() > this.probability) {
                continue;
            }
            Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            ga$tryLeaf(context, random, pos, log.getX() + direction.getStepX(), log.getY(), log.getZ() + direction.getStepZ());
            Direction side = direction.getClockWise();
            pos.move(side);
            ga$tryLeaf(context, random, pos);
            pos.move(side.getOpposite(), 2);
            ga$tryLeaf(context, random, pos);
            pos.move(side).move(direction);
            ga$tryLeaf(context, random, pos);
            pos.move(direction.getOpposite()).move(Direction.UP);
            ga$tryLeaf(context, random, pos);
        }
    }

    @Unique
    private void ga$tryLeaf(TreeDecorator.Context context, RandomSource random, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        pos.set(x, y, z);
        ga$tryLeaf(context, random, pos);
    }

    @Unique
    private void ga$tryLeaf(TreeDecorator.Context context, RandomSource random, BlockPos.MutableBlockPos pos) {
        if (context.isAir(pos)) {
            context.setBlock(pos, this.leaf_provider.getState(random, pos));
        }
    }
}

