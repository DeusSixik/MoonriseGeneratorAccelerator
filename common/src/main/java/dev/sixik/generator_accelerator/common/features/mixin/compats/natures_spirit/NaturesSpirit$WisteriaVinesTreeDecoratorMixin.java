package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import net.hibiscus.naturespirit.world.tree_decorator.WisteriaVinesTreeDecorator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = WisteriaVinesTreeDecorator.class, remap = false)
public abstract class NaturesSpirit$WisteriaVinesTreeDecoratorMixin {
    @Shadow
    @Final
    protected BlockStateProvider blockProvider;
    @Shadow
    @Final
    protected BlockStateProvider blockProvider2;
    @Shadow
    @Final
    protected BlockStateProvider blockProvider3;
    @Shadow
    @Final
    protected BlockStateProvider blockProvider4;
    @Shadow
    @Final
    private float probability;
    @Shadow
    protected int number;

    /**
     * @author Sixik
     * @reason Replace leaves.forEach and below/above allocation chains with mutable positions.
     */
    @Overwrite(remap = false)
    public void place(TreeDecorator.Context context) {
        RandomSource random = context.random();
        List<BlockPos> leaves = context.leaves();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        for (int i = 0; i < leaves.size(); ++i) {
            BlockPos leaf = leaves.get(i);
            if (random.nextFloat() >= this.probability) {
                continue;
            }
            pos.set(leaf.getX(), leaf.getY() - 1, leaf.getZ());
            if (context.isAir(pos)) {
                ga$placeVines(pos, check, context, this.number);
            }
        }
    }

    @Unique
    private void ga$placeVines(BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos check, TreeDecorator.Context context, int count) {
        RandomSource random = context.random();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        context.setBlock(pos, this.blockProvider4.getState(random, pos));
        check.set(x, y + 2, z);
        boolean airTwo = context.isAir(check);
        check.set(x, y + 3, z);
        if (!airTwo || !context.isAir(check)) {
            check.set(x, y + 1, z);
            context.setBlock(check, this.blockProvider3.getState(random, check));
        }

        for (int remaining = count, vineY = y - 1; remaining > 0; --remaining, --vineY) {
            pos.set(x, vineY, z);
            if (!context.isAir(pos)) {
                continue;
            }
            check.set(x, vineY - 1, z);
            if (remaining == 1 || !context.isAir(check) || random.nextBoolean()) {
                context.setBlock(pos, this.blockProvider2.getState(random, pos));
                return;
            }
            context.setBlock(pos, this.blockProvider.getState(random, pos));
        }
    }
}

