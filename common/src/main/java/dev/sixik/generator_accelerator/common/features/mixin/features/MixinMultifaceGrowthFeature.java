package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.features.GAMultifaceSpreaderAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature;
import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = MultifaceGrowthFeature.class, priority = 999)
public abstract class MixinMultifaceGrowthFeature extends Feature<MultifaceGrowthConfiguration> {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$NEIGHBOR_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinMultifaceGrowthFeature(Codec<MultifaceGrowthConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Avoid short-lived Iterator allocations in fallback multiface decoration.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<MultifaceGrowthConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();
        MultifaceGrowthConfiguration config = context.config();
        BlockState originState = level.getBlockState(origin);
        if (!ga$isAirOrWater(originState)) {
            return false;
        }

        List<Direction> directions = config.getShuffledDirections(random);
        if (placeGrowthIfPossible(level, origin, originState, config, random, directions)) {
            return true;
        }

        BlockPos.MutableBlockPos cursor = origin.mutable();
        for (int directionIndex = 0, directionCount = directions.size(); directionIndex < directionCount; directionIndex++) {
            Direction direction = directions.get(directionIndex);
            cursor.set(origin);
            List<Direction> spreadDirections = config.getShuffledDirectionsExcept(random, direction.getOpposite());
            for (int step = 0; step < config.searchRange; step++) {
                cursor.move(direction);
                BlockState state = level.getBlockState(cursor);
                if (!ga$isAirOrWater(state) && !state.is(config.placeBlock)) {
                    break;
                }
                if (placeGrowthIfPossible(level, cursor, state, config, random, spreadDirections)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * @author Sixik
     * @reason Iterate shuffled directions by index instead of allocating a list iterator.
     */
    @Overwrite
    public static boolean placeGrowthIfPossible(
            WorldGenLevel level,
            BlockPos pos,
            BlockState state,
            MultifaceGrowthConfiguration config,
            RandomSource random,
            List<Direction> directions
    ) {
        BlockPos.MutableBlockPos neighbor = GA$NEIGHBOR_POS.get();
        for (int i = 0, size = directions.size(); i < size; i++) {
            Direction direction = directions.get(i);
            BlockState supportState = level.getBlockState(neighbor.setWithOffset(pos, direction));
            if (!supportState.is(config.canBePlacedOn)) {
                continue;
            }

            BlockState placementState = config.placeBlock.getStateForPlacement(state, level, pos, direction);
            if (placementState == null) {
                return false;
            }

            level.setBlock(pos, placementState, 3);
            level.getChunk(pos).markPosForPostprocessing(pos);
            if (random.nextFloat() < config.chanceOfSpreading) {
                ((GAMultifaceSpreaderAccess) config.placeBlock.getSpreader()).ga$spreadFromFaceTowardRandomDirectionNoResult(
                        placementState,
                        level,
                        pos,
                        direction,
                        random,
                        true
                );
            }
            return true;
        }
        return false;
    }

    @Unique
    private static boolean ga$isAirOrWater(BlockState state) {
        return state.isAir() || state.getBlock() == Blocks.WATER;
    }
}
