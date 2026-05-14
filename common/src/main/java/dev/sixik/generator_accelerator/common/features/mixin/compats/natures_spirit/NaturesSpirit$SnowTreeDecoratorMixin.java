package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import net.hibiscus.naturespirit.world.tree_decorator.SnowTreeDecorator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = SnowTreeDecorator.class, remap = false)
public abstract class NaturesSpirit$SnowTreeDecoratorMixin {
    @Unique
    private static final BlockState GA$SNOW = Blocks.SNOW.defaultBlockState();

    /**
     * @author Sixik
     * @reason Collapse two leaf streams into one pass and avoid above/below BlockPos chains.
     */
    @Overwrite(remap = false)
    public void place(TreeDecorator.Context context) {
        List<BlockPos> leaves = context.leaves();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos heightPos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < leaves.size(); ++i) {
            BlockPos leaf = leaves.get(i);
            int x = leaf.getX();
            int y = leaf.getY();
            int z = leaf.getZ();

            pos.set(x, y + 1, z);
            if (context.isAir(pos)) {
                context.setBlock(pos, GA$SNOW);
            }

            pos.set(x, y - 1, z);
            if (!context.isAir(pos)) {
                continue;
            }
            pos.set(x, y - 2, z);
            if (!context.isAir(pos)) {
                continue;
            }
            heightPos.set(x, y, z);
            BlockPos top = context.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heightPos);
            if (context.isAir(top)) {
                context.setBlock(top, GA$SNOW);
            }
        }
    }
}

