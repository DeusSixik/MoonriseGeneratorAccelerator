package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritBlocks;
import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritFeatureUtil;
import com.mojang.serialization.Codec;
import net.hibiscus.naturespirit.world.feature.PolyporeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = PolyporeFeature.class, remap = false)
public abstract class NaturesSpirit$PolyporeFeatureMixin extends Feature<NoneFeatureConfiguration> {
    @Unique
    private static volatile BlockState GA$POLYPORE;

    protected NaturesSpirit$PolyporeFeatureMixin(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Avoid shuffledCopy and chained BlockPos.relative allocations.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel world = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        for (Direction direction : NaturesSpiritFeatureUtil.shuffledHorizontal(random)) {
            pos.set(origin.getX() + direction.getStepX(), origin.getY(), origin.getZ() + direction.getStepZ());
            if (world.isEmptyBlock(pos)) {
                continue;
            }
            Direction clockwise = direction.getClockWise();
            Direction counterClockwise = direction.getCounterClockWise();
            int radius = random.nextIntBetweenInclusive(1, 3);

            check.set(pos.getX() + clockwise.getStepX(), pos.getY(), pos.getZ() + clockwise.getStepZ());
            if (world.isEmptyBlock(check)) {
                check.set(origin.getX() + clockwise.getStepX(), origin.getY(), origin.getZ() + clockwise.getStepZ());
                if (world.isEmptyBlock(check)) {
                    ga$generateSquare(world, pos, radius, direction.getOpposite(), clockwise, check);
                    return true;
                }
            }

            check.set(pos.getX() + counterClockwise.getStepX(), pos.getY(), pos.getZ() + counterClockwise.getStepZ());
            if (world.isEmptyBlock(check)) {
                check.set(origin.getX() + counterClockwise.getStepX(), origin.getY(), origin.getZ() + counterClockwise.getStepZ());
                if (world.isEmptyBlock(check)) {
                    ga$generateSquare(world, pos, radius, direction.getOpposite(), counterClockwise, check);
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private static void ga$generateSquare(LevelAccessor world, BlockPos corner, int radius, Direction direction1, Direction direction2, BlockPos.MutableBlockPos pos) {
        int baseX = corner.getX();
        int baseY = corner.getY();
        int baseZ = corner.getZ();
        for (int j = 0; j <= radius; ++j) {
            int x1 = baseX + direction1.getStepX() * j;
            int z1 = baseZ + direction1.getStepZ() * j;
            for (int k = 0; k <= radius; ++k) {
                pos.set(x1 + direction2.getStepX() * k, baseY, z1 + direction2.getStepZ() * k);
                if (world.isEmptyBlock(pos) || world.getBlockState(pos).is(NaturesSpiritBlocks.grayPolypore())) {
                    world.setBlock(pos, ga$polyporeState(), 2);
                }
            }
        }
    }

    @Unique
    private static BlockState ga$polyporeState() {
        BlockState state = GA$POLYPORE;
        if (state == null) {
            state = NaturesSpiritBlocks.grayPolyporeBlock().defaultBlockState().setValue(HugeMushroomBlock.DOWN, false);
            GA$POLYPORE = state;
        }
        return state;
    }
}



