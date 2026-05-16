package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import com.mojang.serialization.Codec;
import net.hibiscus.naturespirit.world.feature.TurnipRootFeature;
import net.hibiscus.naturespirit.world.feature.TurnipRootFeatureConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = TurnipRootFeature.class, remap = false)
public abstract class NaturesSpirit$TurnipRootFeatureMixin extends Feature<TurnipRootFeatureConfig> {
    protected NaturesSpirit$TurnipRootFeatureMixin(Codec<TurnipRootFeatureConfig> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Remove per-column Predicate allocation and above/below/relative BlockPos churn
     * from rooted turnip generation.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<TurnipRootFeatureConfig> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPos origin = context.origin();
        TurnipRootFeatureConfig config = context.config();
        BlockPos.MutableBlockPos treePos = origin.mutable();
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        if (ga$generateTreeAndRoots(level, context.chunkGenerator(), config, random, treePos, scratch, origin)) {
            ga$generateHangingRoots(level, config, random, origin, treePos, scratch);
            ga$generateTurnips(level, config, random, origin, treePos, scratch);
        }
        return true;
    }

    @Unique
    private static boolean ga$generateTreeAndRoots(
            WorldGenLevel world,
            ChunkGenerator generator,
            TurnipRootFeatureConfig config,
            RandomSource random,
            BlockPos.MutableBlockPos treePos,
            BlockPos.MutableBlockPos scratch,
            BlockPos origin
    ) {
        for (int i = 0; i < config.maxRootColumnHeight; ++i) {
            treePos.move(Direction.UP);
            if (!config.predicate.test(world, treePos) || !ga$hasSpaceForTree(world, config, treePos, scratch)) {
                continue;
            }

            scratch.set(treePos).move(Direction.DOWN);
            BlockState support = world.getBlockState(scratch);
            if (world.getFluidState(scratch).is(FluidTags.LAVA) || !support.isSolid()) {
                return false;
            }

            if (config.feature.value().place(world, generator, random, treePos)) {
                ga$generateRootsColumn(origin, origin.getY() + i, world, config, random, scratch);
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean ga$hasSpaceForTree(WorldGenLevel world, TurnipRootFeatureConfig config, BlockPos pos, BlockPos.MutableBlockPos scratch) {
        scratch.set(pos);
        for (int i = 1; i <= config.requiredVerticalSpaceForTree; ++i) {
            scratch.move(Direction.UP);
            BlockState state = world.getBlockState(scratch);
            if (!state.isAir() && (i + 1 > config.allowedVerticalWaterForTree || !state.getFluidState().is(FluidTags.WATER))) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private static void ga$generateRootsColumn(
            BlockPos origin,
            int maxY,
            WorldGenLevel world,
            TurnipRootFeatureConfig config,
            RandomSource random,
            BlockPos.MutableBlockPos mutable
    ) {
        int x = origin.getX();
        int z = origin.getZ();
        for (int y = origin.getY(); y < maxY; ++y) {
            mutable.set(x, y, z);
            ga$generateRoots(world, config, random, x, z, mutable);
        }
    }

    @Unique
    private static void ga$generateRoots(WorldGenLevel world, TurnipRootFeatureConfig config, RandomSource random, int x, int z, BlockPos.MutableBlockPos pos) {
        int radius = config.rootRadius;
        for (int attempt = 0; attempt < config.rootPlacementAttempts; ++attempt) {
            pos.set(x + random.nextInt(radius) - random.nextInt(radius), pos.getY(), z + random.nextInt(radius) - random.nextInt(radius));
            if (world.getBlockState(pos).is(config.rootReplaceable)) {
                world.setBlock(pos, config.rootStateProvider.getState(random, pos), 2);
            }
            pos.setX(x);
            pos.setZ(z);
        }
    }

    @Unique
    private static void ga$generateHangingRoots(
            WorldGenLevel world,
            TurnipRootFeatureConfig config,
            RandomSource random,
            BlockPos origin,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos above
    ) {
        int radius = config.hangingRootRadius;
        int span = config.hangingRootVerticalSpan;
        for (int attempt = 0; attempt < config.hangingRootPlacementAttempts; ++attempt) {
            ga$randomOffset(pos, origin, random, radius, span);
            if (!world.isEmptyBlock(pos)) {
                continue;
            }
            BlockState state = config.hangingRootStateProvider.getState(random, pos);
            above.set(pos).move(Direction.UP);
            if (state.canSurvive(world, pos) && world.getBlockState(above).isFaceSturdy(world, pos, Direction.DOWN)) {
                world.setBlock(pos, state, 2);
            }
        }
    }

    @Unique
    private static void ga$generateTurnips(
            WorldGenLevel world,
            TurnipRootFeatureConfig config,
            RandomSource random,
            BlockPos origin,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos above
    ) {
        int radius = config.hangingRootRadius;
        int span = config.hangingRootVerticalSpan;
        for (int attempt = 0; attempt < config.turnipPlacementAttempts; ++attempt) {
            ga$randomOffset(pos, origin, random, radius, span);
            above.set(pos).move(Direction.UP);
            if (world.getBlockState(above) != config.rootStateProvider.getState(random, pos)) {
                continue;
            }
            BlockState state = config.turnipStateProvider.getState(random, pos);
            if (state.canSurvive(world, pos) && world.getBlockState(above).isFaceSturdy(world, pos, Direction.DOWN)) {
                world.setBlock(pos, state, 2);
            }
        }
    }

    @Unique
    private static void ga$randomOffset(BlockPos.MutableBlockPos pos, BlockPos origin, RandomSource random, int radius, int verticalSpan) {
        int dx = random.nextInt(radius) - random.nextInt(radius);
        int dy = verticalSpan <= 0 ? 0 : random.nextInt(verticalSpan) - random.nextInt(verticalSpan);
        int dz = random.nextInt(radius) - random.nextInt(radius);
        pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
    }
}

