package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.BlockPileFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.BlockPileConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = BlockPileFeature.class, priority = 999)
public abstract class MixinBlockPileFeature extends Feature<BlockPileConfiguration> {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$BELOW_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinBlockPileFeature(Codec<BlockPileConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Replace `betweenClosed` iteration and per-block position allocation with raw loops and reused mutable positions.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<BlockPileConfiguration> placeContext) {
        BlockPos origin = placeContext.origin();
        WorldGenLevel level = placeContext.level();
        RandomSource random = placeContext.random();
        BlockPileConfiguration config = placeContext.config();

        int originY = origin.getY();
        if (originY < level.getMinBuildHeight() + 5) {
            return false;
        }

        int radiusX = 2 + random.nextInt(2);
        int radiusZ = 2 + random.nextInt(2);
        int originX = origin.getX();
        int originZ = origin.getZ();
        int minX = originX - radiusX;
        int maxX = originX + radiusX;
        int minZ = originZ - radiusZ;
        int maxZ = originZ + radiusZ;
        int maxY = originY + 1;

        BlockPos.MutableBlockPos pos = GA$POS.get();
        BlockPos.MutableBlockPos belowPos = GA$BELOW_POS.get();

        for (int z = minZ; z <= maxZ; z++) {
            int dz = originZ - z;
            int dzSq = dz * dz;
            for (int y = originY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int dx = originX - x;
                    float distanceScore = (float) (dx * dx + dzSq);
                    if (distanceScore <= random.nextFloat() * 10.0F - random.nextFloat() * 6.0F) {
                        ga$tryPlaceBlock(level, config, pos, belowPos, random, x, y, z);
                    } else if (random.nextFloat() < 0.031F) {
                        ga$tryPlaceBlock(level, config, pos, belowPos, random, x, y, z);
                    }
                }
            }
        }

        return true;
    }

    @Unique
    private void ga$tryPlaceBlock(
            LevelAccessor level,
            BlockPileConfiguration config,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos belowPos,
            RandomSource random,
            int x,
            int y,
            int z
    ) {
        pos.set(x, y, z);
        if (level.isEmptyBlock(pos) && ga$mayPlaceOn(level, pos, belowPos, random)) {
            level.setBlock(pos, config.stateProvider.getState(random, pos), 4);
        }
    }

    @Unique
    private static boolean ga$mayPlaceOn(
            LevelAccessor level,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos belowPos,
            RandomSource random
    ) {
        belowPos.setWithOffset(pos, Direction.DOWN);
        BlockState belowState = level.getBlockState(belowPos);
        if (belowState.is(Blocks.DIRT_PATH)) {
            return random.nextBoolean();
        }
        return belowState.isFaceSturdy(level, belowPos, Direction.UP);
    }
}
