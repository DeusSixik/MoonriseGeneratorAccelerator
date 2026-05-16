package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritBlocks;
import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritFeatureUtil;
import com.mojang.serialization.Codec;
import net.hibiscus.naturespirit.blocks.BranchingTrunkBlock;
import net.hibiscus.naturespirit.world.feature.JoshuaTreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = JoshuaTreeFeature.class, remap = false)
public abstract class NaturesSpirit$JoshuaTreeFeatureMixin extends Feature<NoneFeatureConfiguration> {
    protected NaturesSpirit$JoshuaTreeFeatureMixin(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Avoid repeated BranchingTrunkBlock.withConnectionProperties scans and duplicate
     * writes while preserving the recursive Joshua tree shape.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        return ga$generate(level, context.origin(), context.random(), 0);
    }

    @Unique
    private static boolean ga$generate(LevelAccessor world, BlockPos pos, RandomSource random, int layer) {
        BranchingTrunkBlock block = (BranchingTrunkBlock) NaturesSpiritBlocks.joshuaLog();
        BlockState vertical = NaturesSpiritFeatureUtil.verticalState(block);
        BlockState top = NaturesSpiritFeatureUtil.capState(block);
        BlockState leaves = NaturesSpiritBlocks.joshuaLeaves().defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        int baseX = pos.getX();
        int baseY = pos.getY();
        int baseZ = pos.getZ();
        int height = random.nextIntBetweenInclusive(1, 3) + (layer == 0 ? 2 : 0);

        for (int j = 0; j < height; ++j) {
            current.set(baseX, baseY + j + 1, baseZ);
            if (!NaturesSpiritFeatureUtil.isSurroundedByAir(world, current, Direction.DOWN, check)) {
                return false;
            }
            world.setBlock(current, j + 1 == height ? top : vertical, 2);
            current.set(baseX, baseY + j, baseZ);
            world.setBlock(current, vertical, 2);
            if (layer > 0) {
                current.set(baseX, baseY + j - 1, baseZ);
                world.setBlock(current, vertical, 2);
            }
        }

        if (layer < 2) {
            int branchAttempts = random.nextIntBetweenInclusive(3, 5) + (layer == 0 ? 1 : 0);
            for (int l = 0; l < branchAttempts; ++l) {
                Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(random);
                int length = random.nextIntBetweenInclusive(1, 2);
                int n = Math.max(1, height - length);
                int branchX = baseX + direction.getStepX() * length;
                int branchY = baseY + n;
                int branchZ = baseZ + direction.getStepZ() * length;
                current.set(branchX, branchY, branchZ);

                if (!world.isEmptyBlock(current) || !NaturesSpiritFeatureUtil.isSurroundedByAir(world, current, direction.getOpposite(), check)) {
                    continue;
                }

                BlockState arm = NaturesSpiritFeatureUtil.horizontalState(block, direction);
                world.setBlock(current, arm, 2);
                for (int p = length; p > 0; --p) {
                    current.set(branchX - direction.getStepX() * p, branchY, branchZ - direction.getStepZ() * p);
                    world.setBlock(current, arm, 2);
                }

                ga$generate(world, new BlockPos(branchX, branchY + 1, branchZ), random, layer + 1);
                ga$placeLeafCap(world, random, block, leaves, branchX, branchY, branchZ, direction, current);
            }
            return true;
        }

        current.set(baseX, baseY + height, baseZ);
        world.setBlock(current, leaves, 2);
        current.set(baseX, baseY + height - 1, baseZ);
        world.setBlock(current, top, 2);
        Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        current.set(baseX + direction.getStepX(), baseY + height - 1, baseZ + direction.getStepZ());
        if (world.isEmptyBlock(current)) {
            world.setBlock(current, leaves, 2);
            current.set(baseX, baseY + height - 1, baseZ);
            world.setBlock(current, NaturesSpiritFeatureUtil.connectedState(block, Direction.DOWN, direction), 2);
        }
        return true;
    }

    @Unique
    private static void ga$placeLeafCap(
            LevelAccessor world,
            RandomSource random,
            BranchingTrunkBlock block,
            BlockState leaves,
            int x,
            int y,
            int z,
            Direction clearanceDirection,
            BlockPos.MutableBlockPos current
    ) {
        current.set(x, y + 1, z);
        if (world.isEmptyBlock(current)) {
            world.setBlock(current, leaves, 2);
            current.set(x, y, z);
            world.setBlock(current, NaturesSpiritFeatureUtil.capState(block), 2);
            Direction leafDirection = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            current.set(x + clearanceDirection.getStepX(), y, z + clearanceDirection.getStepZ());
            if (random.nextFloat() < 0.65F && world.isEmptyBlock(current)) {
                current.set(x + leafDirection.getStepX(), y, z + leafDirection.getStepZ());
                world.setBlock(current, leaves, 2);
            }
            return;
        }

        current.set(x, y + 2, z);
        if (world.isEmptyBlock(current)) {
            world.setBlock(current, leaves, 2);
            current.set(x, y + 1, z);
            world.setBlock(current, NaturesSpiritFeatureUtil.verticalState(block), 2);
            Direction leafDirection = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            current.set(x + clearanceDirection.getStepX(), y + 1, z + clearanceDirection.getStepZ());
            if (random.nextFloat() < 0.65F && world.isEmptyBlock(current)) {
                current.set(x + leafDirection.getStepX(), y + 1, z + leafDirection.getStepZ());
                world.setBlock(current, leaves, 2);
            }
        }
    }
}



