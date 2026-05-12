package dev.sixik.generator_accelerator.common.features.mixin.compats.galosphere;

import dev.sixik.generator_accelerator.common.features.compat.galosphere.GACrystalSpikeConfigAccessors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashSet;

@Mixin(targets = "net.orcinus.galosphere.world.gen.features.CrystalSpikeFeature", remap = false)
public abstract class Galosphere$CrystalSpikeFeatureMixin {
    @Unique
    private static final Direction[] GA$DIRECTIONS = Direction.values();

    @Unique
    private static final BlockState GA$CALCITE = Blocks.CALCITE.defaultBlockState();

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$PLACE_SIDE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SPIKE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SPIKE_SCAN_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SPIKE_SIDE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SPIKE_TRIG_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$BLOOM_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$BLOOM_SIDE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Avoid Galosphere's per-cell BlockPos allocations, Direction.values() clones,
     * ConstantInt allocation in calcite bloom and repeated trig math inside spike scans.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<?> context) {
        WorldGenLevel world = context.level();
        BlockPos blockPos = context.origin();
        RandomSource random = context.random();
        Object config = context.config();
        GACrystalSpikeConfigAccessors accessors = GACrystalSpikeConfigAccessors.of(config);

        CaveSurface caveSurface = accessors.crystalDirection(config);
        Direction direction = caveSurface.getDirection();
        Direction opposite = direction.getOpposite();
        BlockPos.MutableBlockPos supportCheck = GA$PLACE_SIDE_POS.get();
        supportCheck.set(
                blockPos.getX() + opposite.getStepX(),
                blockPos.getY() + opposite.getStepY(),
                blockPos.getZ() + opposite.getStepZ()
        );

        if (!world.isStateAtPosition(supportCheck, DripstoneUtils::isEmptyOrWaterOrLava)
                || !world.getBlockState(blockPos).is(BlockTags.BASE_STONE_OVERWORLD)) {
            return false;
        }

        HashSet<BlockPos> trigList = new HashSet<>();
        HashSet<BlockPos> clusterPos = new HashSet<>();
        int radiusCheck = accessors.xzRadius(config).sample(random) + 1;
        int randomChance = random.nextInt(4);
        int stepHeight = radiusCheck + 14 + Mth.nextInt(random, 10, 14);

        if (!this.ga$placeSpike(
                world,
                blockPos.getX(),
                blockPos.getY(),
                blockPos.getZ(),
                radiusCheck,
                stepHeight,
                randomChance,
                trigList,
                direction,
                random
        )) {
            return false;
        }

        return this.ga$placeCrystals(
                world,
                random,
                accessors.crystalState(config),
                accessors.clusterState(config),
                accessors.glintedCluster(config),
                accessors.glintedClusterChance(config),
                trigList,
                clusterPos,
                false
        );
    }

    @Unique
    private boolean ga$placeCrystals(
            WorldGenLevel world,
            RandomSource random,
            BlockState crystalState,
            BlockState clusterState,
            BlockState glintedCluster,
            float glintedClusterChance,
            HashSet<BlockPos> trigList,
            HashSet<BlockPos> clusterPos,
            boolean flag
    ) {
        for (BlockPos pos : trigList) {
            if (!world.isStateAtPosition(pos, DripstoneUtils::isEmptyOrWaterOrLava)) {
                continue;
            }
            world.setBlock(pos, crystalState, 3);
            clusterPos.add(pos);
            flag = true;
        }

        BlockPos.MutableBlockPos relative = GA$SPIKE_SIDE_POS.get();
        for (BlockPos pos : clusterPos) {
            if (random.nextInt(6) != 0 || !world.getBlockState(pos).equals(crystalState)) {
                continue;
            }
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            for (int i = 0; i < GA$DIRECTIONS.length; i++) {
                Direction direction = GA$DIRECTIONS[i];
                if (!random.nextBoolean()) {
                    continue;
                }
                relative.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                if (!world.isStateAtPosition(relative, DripstoneUtils::isEmptyOrWater)) {
                    continue;
                }
                BlockState state = random.nextFloat() > glintedClusterChance ? clusterState : glintedCluster;
                world.setBlock(relative, state
                        .setValue(AmethystClusterBlock.FACING, direction)
                        .setValue(AmethystClusterBlock.WATERLOGGED, world.getFluidState(relative).getType() == Fluids.WATER), 3);
            }
        }
        return flag;
    }

    @Unique
    private boolean ga$placeSpike(
            LevelAccessor world,
            int baseX,
            int baseY,
            int baseZ,
            int startRadius,
            int height,
            int randomChance,
            HashSet<BlockPos> crystalPos,
            Direction direction,
            RandomSource random
    ) {
        if (startRadius < 1) {
            return false;
        }

        float delta = switch (randomChance) {
            case 1 -> 5.759587F;
            case 2 -> 0.5235988F;
            case 3 -> 3.6651917F;
            case 0 -> 2.617994F;
            default -> throw new IllegalStateException("Unexpected value: " + randomChance);
        };
        float cosDelta = Mth.cos(delta);
        float sinDelta = Mth.sin(delta);
        boolean up = direction == Direction.UP;
        int directionStepX = direction.getStepX();
        int directionStepY = direction.getStepY();
        int directionStepZ = direction.getStepZ();

        boolean flag = false;
        BlockPos.MutableBlockPos pos = GA$SPIKE_POS.get();
        BlockPos.MutableBlockPos scan = GA$SPIKE_SCAN_POS.get();
        BlockPos.MutableBlockPos side = GA$SPIKE_SIDE_POS.get();
        BlockPos.MutableBlockPos trig = GA$SPIKE_TRIG_POS.get();

        for (int y = 0; y < height; y++) {
            int radius = startRadius - y / 2;
            int radiusSq = radius * radius;
            float q = cosDelta * y;
            float l = sinDelta * y;
            float trigOffsetX = up ? -q : q;
            float trigOffsetY = up ? -y : y;
            float trigOffsetZ = up ? -l : l;

            for (int x = -radius; x <= radius; x++) {
                int xSq = x * x;
                for (int z = -radius; z <= radius; z++) {
                    if (xSq + z * z > radiusSq) {
                        continue;
                    }

                    pos.set(baseX + x, baseY, baseZ + z);
                    if (direction == Direction.DOWN) {
                        side.set(pos.getX(), pos.getY() - 1, pos.getZ());
                        if (world.isStateAtPosition(side, DripstoneUtils::isEmptyOrWaterOrLava)) {
                            return this.ga$placeSpike(
                                    world,
                                    baseX,
                                    baseY - 1,
                                    baseZ,
                                    startRadius / 2,
                                    height,
                                    randomChance,
                                    crystalPos,
                                    direction,
                                    random
                            );
                        }
                    } else if (up) {
                        scan.set(pos);
                        for (int i = 0; i < 10; i++) {
                            side.set(scan.getX(), scan.getY() + 1, scan.getZ());
                            if (!world.isStateAtPosition(side, DripstoneUtils::isEmptyOrWaterOrLava)) {
                                break;
                            }
                            scan.move(Direction.UP);
                        }
                        pos.set(scan);
                        side.set(pos.getX(), pos.getY() + 1, pos.getZ());
                        if (world.isStateAtPosition(side, DripstoneUtils::isEmptyOrWaterOrLava)) {
                            return false;
                        }
                    }

                    side.set(pos.getX() + directionStepX, pos.getY() + directionStepY, pos.getZ() + directionStepZ);
                    this.ga$calciteBloom(world, side, radius);

                    trig.set(
                            Mth.floor(pos.getX() + trigOffsetX),
                            Mth.floor(pos.getY() + trigOffsetY),
                            Mth.floor(pos.getZ() + trigOffsetZ)
                    );
                    if (world.isStateAtPosition(trig, DripstoneUtils::isEmptyOrWaterOrLava)) {
                        crystalPos.add(trig.immutable());
                        flag = true;
                    } else {
                        crystalPos.remove(trig);
                    }
                }
            }
        }
        return flag;
    }

    @Unique
    private boolean ga$calciteBloom(LevelAccessor world, BlockPos blockPos, int crystalRadius) {
        int radius = crystalRadius >> 2;
        boolean flag = false;
        BlockPos.MutableBlockPos pos = GA$BLOOM_POS.get();
        BlockPos.MutableBlockPos side = GA$BLOOM_SIDE_POS.get();
        int baseX = blockPos.getX();
        int baseY = blockPos.getY();
        int baseZ = blockPos.getZ();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -2; y <= 2; y++) {
                    pos.set(baseX + x, baseY + y, baseZ + z);
                    if (!world.getBlockState(pos).is(BlockTags.BASE_STONE_OVERWORLD)) {
                        continue;
                    }
                    for (int i = 0; i < GA$DIRECTIONS.length; i++) {
                        Direction direction = GA$DIRECTIONS[i];
                        side.set(pos.getX() + direction.getStepX(), pos.getY() + direction.getStepY(), pos.getZ() + direction.getStepZ());
                        if (!world.isStateAtPosition(side, DripstoneUtils::isEmptyOrWaterOrLava)) {
                            continue;
                        }
                        world.setBlock(pos, GA$CALCITE, 2);
                        flag = true;
                        break;
                    }
                }
            }
        }
        return flag;
    }

}
