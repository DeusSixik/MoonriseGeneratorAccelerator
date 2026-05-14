package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritFeatureUtil;
import net.hibiscus.naturespirit.blocks.PolyporeBlock;
import net.hibiscus.naturespirit.world.tree_decorator.PolyporeTreeDecorator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = PolyporeTreeDecorator.class, remap = false)
public abstract class NaturesSpirit$PolyporeTreeDecoratorMixin {
    @Shadow
    @Final
    private float big_probability;
    @Shadow
    @Final
    private float small_probability;
    @Shadow
    @Final
    private float chance;
    @Shadow
    @Final
    private BlockStateProvider block_provider;
    @Shadow
    @Final
    private BlockStateProvider polypore_provider;

    /**
     * @author Sixik
     * @reason Avoid streams, shuffledCopy list allocation and chained BlockPos.relative calls.
     */
    @Overwrite(remap = false)
    public void place(TreeDecorator.Context context) {
        RandomSource random = context.random();
        if (random.nextFloat() >= this.chance) {
            return;
        }

        List<BlockPos> logs = context.logs();
        if (logs.isEmpty()) {
            return;
        }
        int baseY = logs.get(0).getY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();

        for (int i = 0; i < logs.size(); ++i) {
            BlockPos log = logs.get(i);
            if (log.getY() >= baseY + 6 || log.getY() <= baseY) {
                continue;
            }

            if (random.nextFloat() <= this.big_probability) {
                Direction[] directions = NaturesSpiritFeatureUtil.shuffledHorizontal(random);
                for (Direction direction : directions) {
                    pos.set(log.getX() + direction.getStepX(), log.getY(), log.getZ() + direction.getStepZ());
                    if (!context.isAir(pos)) {
                        continue;
                    }
                    Direction clockwise = direction.getClockWise();
                    Direction counterClockwise = direction.getCounterClockWise();
                    int radius = random.nextIntBetweenInclusive(1, 2);
                    side.set(log.getX() + clockwise.getStepX(), log.getY(), log.getZ() + clockwise.getStepZ());
                    pos.set(side.getX() + direction.getStepX(), side.getY(), side.getZ() + direction.getStepZ());
                    if (context.isAir(side) && context.isAir(pos)) {
                        ga$generateSquare(context, log, radius, direction, clockwise, random, pos);
                        break;
                    }
                    side.set(log.getX() + counterClockwise.getStepX(), log.getY(), log.getZ() + counterClockwise.getStepZ());
                    pos.set(side.getX() + direction.getStepX(), side.getY(), side.getZ() + direction.getStepZ());
                    if (context.isAir(side) && context.isAir(pos)) {
                        ga$generateSquare(context, log, radius, direction, counterClockwise, random, pos);
                        break;
                    }
                }
            }

            if (random.nextFloat() <= this.small_probability) {
                Direction[] directions = NaturesSpiritFeatureUtil.shuffledHorizontal(random);
                for (Direction direction : directions) {
                    pos.set(log.getX() + direction.getStepX(), log.getY(), log.getZ() + direction.getStepZ());
                    if (!context.isAir(pos)) {
                        continue;
                    }
                    context.setBlock(pos, this.polypore_provider.getState(random, pos).trySetValue(PolyporeBlock.FACING, direction));
                    Direction clockwise = direction.getClockWise();
                    pos.set(log.getX() + clockwise.getStepX(), log.getY(), log.getZ() + clockwise.getStepZ());
                    if (context.isAir(pos)) {
                        context.setBlock(pos, this.polypore_provider.getState(random, pos).trySetValue(PolyporeBlock.FACING, clockwise));
                    }
                    break;
                }
            }
        }
    }

    @Unique
    private void ga$generateSquare(
            TreeDecorator.Context context,
            BlockPos corner,
            int radius,
            Direction direction1,
            Direction direction2,
            RandomSource random,
            BlockPos.MutableBlockPos pos
    ) {
        int baseX = corner.getX();
        int baseY = corner.getY();
        int baseZ = corner.getZ();
        for (int j = 0; j <= radius; ++j) {
            int x1 = baseX + direction1.getStepX() * j;
            int z1 = baseZ + direction1.getStepZ() * j;
            for (int k = 0; k <= radius; ++k) {
                pos.set(x1 + direction2.getStepX() * k, baseY, z1 + direction2.getStepZ() * k);
                if (context.isAir(pos)) {
                    context.setBlock(pos, this.block_provider.getState(random, pos).trySetValue(HugeMushroomBlock.DOWN, false));
                }
            }
        }
    }
}

