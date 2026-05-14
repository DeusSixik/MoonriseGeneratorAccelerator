package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritBlocks;
import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritFeatureUtil;
import com.mojang.serialization.Codec;
import net.hibiscus.naturespirit.blocks.GrowingBranchingTrunkBlock;
import net.hibiscus.naturespirit.world.feature.AlluaudiaFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = AlluaudiaFeature.class, remap = false)
public abstract class NaturesSpirit$AlluaudiaFeatureMixin extends Feature<NoneFeatureConfiguration> {
    protected NaturesSpirit$AlluaudiaFeatureMixin(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Nature's Spirit recomputes branching connections by scanning neighbors after
     * almost every block write. Generate equivalent connected states directly and avoid
     * duplicate writes/BlockPos churn.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        ga$generate(level, origin, context.random(), origin, 6, 0);
        return true;
    }

    @Unique
    private static void ga$generate(LevelAccessor world, BlockPos pos, RandomSource random, BlockPos rootPos, int size, int layer) {
        GrowingBranchingTrunkBlock block = (GrowingBranchingTrunkBlock) NaturesSpiritBlocks.alluaudia();
        BlockState vertical = NaturesSpiritFeatureUtil.verticalState(block);
        BlockState top = NaturesSpiritFeatureUtil.capState(block);
        BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        int stemHeight = layer == 0 ? 3 : 2;
        int baseX = pos.getX();
        int baseY = pos.getY();
        int baseZ = pos.getZ();

        for (int j = 0; j < stemHeight; ++j) {
            int yOffset = layer > 0 ? (j == 0 ? 1 : (int) (j / 0.93D)) : j + 1;
            current.set(baseX, baseY + yOffset, baseZ);
            if (!NaturesSpiritFeatureUtil.isSurroundedByAir(world, current, Direction.getRandom(random), check)) {
                return;
            }
            world.setBlock(current, j + 1 == stemHeight ? top : vertical, 2);
            current.set(baseX, baseY + yOffset - 1, baseZ);
            world.setBlock(current, vertical, 2);
        }

        if (layer >= 2) {
            return;
        }

        int branchAttempts = random.nextInt(2) + 4 + (layer == 0 ? 1 : 0);
        for (int l = 0; l < branchAttempts; ++l) {
            Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            Direction side = direction.getClockWise();
            int m = random.nextInt(2);
            int n = stemHeight - m == 0 ? 1 : stemHeight - m;
            int branchX = baseX + direction.getStepX();
            int branchY = baseY + n;
            int branchZ = baseZ + direction.getStepZ();

            if (Math.abs(branchX - rootPos.getX()) >= size || Math.abs(branchZ - rootPos.getZ()) >= size) {
                continue;
            }
            current.set(branchX, branchY, branchZ);
            check.set(branchX, branchY - 1, branchZ);
            if (!world.isEmptyBlock(current) || !world.isEmptyBlock(check)) {
                continue;
            }

            BlockState branch = NaturesSpiritFeatureUtil.connectedState(block, direction.getOpposite(), side);
            world.setBlock(current, branch, 2);
            current.set(branchX - direction.getStepX(), branchY, branchZ - direction.getStepZ());
            world.setBlock(current, NaturesSpiritFeatureUtil.horizontalState(block, direction), 2);

            int sideX = branchX + side.getStepX();
            int sideZ = branchZ + side.getStepZ();
            for (int dy = 0; dy <= 2; ++dy) {
                current.set(sideX, branchY + dy, sideZ);
                world.setBlock(current, vertical, 2);
            }

            ga$generate(world, new BlockPos(baseX, baseY + n + 1, baseZ), random, rootPos, size, layer + 1);

            if (random.nextBoolean()) {
                current.set(branchX + side.getStepX() + direction.getStepX(), branchY + 1, branchZ + side.getStepZ() + direction.getStepZ());
                world.setBlock(current, branch, 2);
            } else {
                current.set(branchX + side.getStepX() * 2, branchY + 1, branchZ + side.getStepZ() * 2);
                world.setBlock(current, NaturesSpiritFeatureUtil.horizontalState(block, side), 2);
            }
            current.set(sideX, branchY + 1, sideZ);
            world.setBlock(current, vertical, 2);
        }
    }
}



