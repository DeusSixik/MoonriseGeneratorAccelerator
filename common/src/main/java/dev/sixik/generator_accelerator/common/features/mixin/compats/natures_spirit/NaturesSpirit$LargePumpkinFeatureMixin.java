package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import com.mojang.serialization.Codec;
import net.hibiscus.naturespirit.world.feature.LargePumpkinFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.BlockPileConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = LargePumpkinFeature.class, remap = false)
public abstract class NaturesSpirit$LargePumpkinFeatureMixin extends Feature<BlockPileConfiguration> {
    protected NaturesSpirit$LargePumpkinFeatureMixin(Codec<BlockPileConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Avoid repeated origin.relative(...).above()/below() allocations in pumpkin piles.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<BlockPileConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom());
        Direction side = direction.getClockWise();
        BlockState state = context.config().stateProvider.getState(context.random(), origin);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();

        if (!ga$isAir(level, pos, x, y + 1, z)
                || !ga$isAir(level, pos, x + direction.getStepX(), y + 1, z + direction.getStepZ())
                || !ga$isAir(level, pos, x + side.getStepX(), y + 1, z + side.getStepZ())
                || !ga$isAir(level, pos, x + direction.getStepX() + side.getStepX(), y + 1, z + direction.getStepZ() + side.getStepZ())) {
            return false;
        }

        ga$setSquare(level, pos, state, x, y, z, direction, side);
        pos.set(x, y - 1, z);
        if (level.isEmptyBlock(pos) || level.getBlockState(pos).is(BlockTags.DIRT)) {
            ga$setSquare(level, pos, state, x, y - 1, z, direction, side);
        }

        if (context.random().nextBoolean()) {
            ga$set(level, pos, state, x, y + 1, z);
        }
        if (context.random().nextBoolean()) {
            ga$set(level, pos, state, x + direction.getStepX(), y + 1, z + direction.getStepZ());
        }
        if (context.random().nextBoolean()) {
            ga$set(level, pos, state, x + side.getStepX(), y + 1, z + side.getStepZ());
        }
        if (context.random().nextBoolean()) {
            ga$set(level, pos, state, x + direction.getStepX() + side.getStepX(), y + 1, z + direction.getStepZ() + side.getStepZ());
        }
        return true;
    }

    @Unique
    private static boolean ga$isAir(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        pos.set(x, y, z);
        return level.isEmptyBlock(pos);
    }

    @Unique
    private static void ga$setSquare(WorldGenLevel level, BlockPos.MutableBlockPos pos, BlockState state, int x, int y, int z, Direction direction, Direction side) {
        ga$set(level, pos, state, x, y, z);
        ga$set(level, pos, state, x + direction.getStepX(), y, z + direction.getStepZ());
        ga$set(level, pos, state, x + side.getStepX(), y, z + side.getStepZ());
        ga$set(level, pos, state, x + direction.getStepX() + side.getStepX(), y, z + direction.getStepZ() + side.getStepZ());
    }

    @Unique
    private static void ga$set(WorldGenLevel level, BlockPos.MutableBlockPos pos, BlockState state, int x, int y, int z) {
        pos.set(x, y, z);
        level.setBlock(pos, state, 1);
    }
}

