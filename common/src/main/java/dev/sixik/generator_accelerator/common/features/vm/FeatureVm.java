package dev.sixik.generator_accelerator.common.features.vm;

import dev.sixik.generator_accelerator.api.exceptions.MethodNotImplementedException;
import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskExtension;
import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$CountOnEveryLayerPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$EnvironmentScanPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$FixedPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$HeightRangePlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$HeightmapPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementFilterAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$RandomOffsetPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$RepeatingPlacementAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

public final class FeatureVm {
    private static final ThreadLocal<FeatureScratchStack> SCRATCH_STACK = ThreadLocal.withInitial(FeatureScratchStack::new);

    private FeatureVm() {
    }

    public static boolean execute(FeatureProgram program, PlacementContext context, RandomSource random, BlockPos startPos) {
        FeatureVmMetrics.recordProgramExecution();
        FeatureScratchStack stack = SCRATCH_STACK.get();
        FeatureScratch scratch = stack.acquire();

        try {
            return executeDepthFirst(program, context, random, startPos.asLong(), 0, scratch);
        } finally {
            stack.release(scratch);
        }
    }

    private static boolean executeDepthFirst(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        if (opIndex >= program.opCount()) {
            return placeFeature(program, context, random, packedPos);
        }

        int opcode = program.opcode(opIndex);
        PlacementModifier modifier = program.modifier(opIndex);
        if (opcode == FeatureOpcode.VANILLA_FALLBACK) {
            return executeVanillaFallback(program, modifier, context, random, packedPos, opIndex, scratch);
        }

        FeatureVmMetrics.recordFastOpExecution();
        LongScratchBuffer output = scratch.buffer(opIndex);
        applyOpcode(opcode, modifier, context, random, packedPos, opIndex, output, scratch);

        boolean success = false;
        for (int i = 0; i < output.size(); i++) {
            if (executeDepthFirst(program, context, random, output.getLong(i), opIndex + 1, scratch)) {
                success = true;
            }
        }
        return success;
    }

    private static void applyOpcode(int opcode, PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        switch (opcode) {
            case FeatureOpcode.IN_SQUARE -> applyInSquare(random, packedPos, output);
            case FeatureOpcode.HEIGHT_RANGE -> applyHeightRange(modifier, context, random, packedPos, output);
            case FeatureOpcode.HEIGHTMAP -> applyHeightmap(modifier, context, packedPos, output);
            case FeatureOpcode.RANDOM_OFFSET -> applyRandomOffset(modifier, random, packedPos, output);
            case FeatureOpcode.REPEATING -> applyRepeating(modifier, random, packedPos, opIndex, output, scratch);
            case FeatureOpcode.PLACEMENT_FILTER -> applyPlacementFilter(modifier, context, random, packedPos, opIndex, output, scratch);
            case FeatureOpcode.FIXED -> applyFixed(modifier, packedPos, output);
            case FeatureOpcode.CARVING_MASK -> applyCarvingMask(modifier, context, packedPos, output);
            case FeatureOpcode.ENVIRONMENT_SCAN -> applyEnvironmentScan(modifier, context, packedPos, opIndex, output, scratch);
            case FeatureOpcode.COUNT_ON_EVERY_LAYER -> applyCountOnEveryLayer(modifier, context, random, packedPos, opIndex, output, scratch);
            case FeatureOpcode.RAW_MODIFIER -> applyFastModifier(modifier, context, random, packedPos, opIndex, output, scratch);
            default -> fillVanillaModifier(modifier, context, random, packedPos, opIndex, output, scratch);
        }
    }

    private static void applyInSquare(RandomSource random, long packedPos, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos) + random.nextInt(16);
        int z = BlockPos.getZ(packedPos) + random.nextInt(16);
        output.add(BlockPos.asLong(x, BlockPos.getY(packedPos), z));
    }

    private static void applyHeightRange(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        GA$HeightRangePlacementAccess access = (GA$HeightRangePlacementAccess) modifier;
        output.add(BlockPos.asLong(BlockPos.getX(packedPos), access.ga$heightProvider().sample(random, context), BlockPos.getZ(packedPos)));
    }

    private static void applyHeightmap(PlacementModifier modifier, PlacementContext context, long packedPos, LongScratchBuffer output) {
        GA$HeightmapPlacementAccess access = (GA$HeightmapPlacementAccess) modifier;
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int y = context.getHeight(access.ga$heightmapType(), x, z);
        if (y > context.getMinBuildHeight()) {
            output.add(BlockPos.asLong(x, y, z));
        }
    }

    private static void applyRandomOffset(PlacementModifier modifier, RandomSource random, long packedPos, LongScratchBuffer output) {
        GA$RandomOffsetPlacementAccess access = (GA$RandomOffsetPlacementAccess) modifier;
        int x = BlockPos.getX(packedPos) + access.ga$xzSpread().sample(random);
        int y = BlockPos.getY(packedPos) + access.ga$ySpread().sample(random);
        int z = BlockPos.getZ(packedPos) + access.ga$xzSpread().sample(random);
        output.add(BlockPos.asLong(x, y, z));
    }

    private static void applyRepeating(PlacementModifier modifier, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(packedPos);
        int count = ((GA$RepeatingPlacementAccess) modifier).ga$repeatingCount(random, pos);
        for (int i = 0; i < count; i++) {
            output.add(packedPos);
        }
    }

    private static void applyPlacementFilter(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(packedPos);
        if (((GA$PlacementFilterAccess) modifier).ga$shouldPlace(context, random, pos)) {
            output.add(packedPos);
        }
    }

    private static void applyFixed(PlacementModifier modifier, long packedPos, LongScratchBuffer output) {
        int chunkX = SectionPos.blockToSectionCoord(BlockPos.getX(packedPos));
        int chunkZ = SectionPos.blockToSectionCoord(BlockPos.getZ(packedPos));
        List<BlockPos> positions = ((GA$FixedPlacementAccess) modifier).ga$fixedPositions();

        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            if (chunkX == SectionPos.blockToSectionCoord(pos.getX())
                    && chunkZ == SectionPos.blockToSectionCoord(pos.getZ())) {
                output.add(pos.asLong());
            }
        }
    }

    private static void applyCarvingMask(PlacementModifier modifier, PlacementContext context, long packedPos, LongScratchBuffer output) {
        int bx = BlockPos.getX(packedPos);
        int bz = BlockPos.getZ(packedPos);
        ChunkPos chunkPos = new ChunkPos(bx >> 4, bz >> 4);
        CarvingMask mask = context.getCarvingMask(chunkPos, ((GA$CarvingMaskPlacementAccess) modifier).ga$carvingStep());
        if (mask != null) {
            ((GA$CarvingMaskExtension) mask).bts$addPositionsRaw(chunkPos, output);
        }
    }

    private static void applyEnvironmentScan(PlacementModifier modifier, PlacementContext context, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        GA$EnvironmentScanPlacementAccess access = (GA$EnvironmentScanPlacementAccess) modifier;
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(x, y, z);
        WorldGenLevel level = context.getLevel();

        if (!access.ga$allowedSearchCondition().test(level, pos)) {
            return;
        }

        Direction direction = access.ga$directionOfSearch();
        int stepX = direction.getStepX();
        int stepY = direction.getStepY();
        int stepZ = direction.getStepZ();

        for (int i = 0; i < access.ga$maxSteps(); i++) {
            if (access.ga$targetCondition().test(level, pos)) {
                output.add(pos.asLong());
                return;
            }

            x += stepX;
            y += stepY;
            z += stepZ;
            pos.set(x, y, z);

            if (level.isOutsideBuildHeight(y)) {
                return;
            }

            if (!access.ga$allowedSearchCondition().test(level, pos)) {
                break;
            }
        }

        if (access.ga$targetCondition().test(level, pos)) {
            output.add(pos.asLong());
        }
    }

    private static void applyCountOnEveryLayer(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        int startX = BlockPos.getX(packedPos);
        int startZ = BlockPos.getZ(packedPos);
        IntProvider count = ((GA$CountOnEveryLayerPlacementAccess) modifier).ga$countProvider();
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex);

        int layer = 0;
        boolean foundOnLayer;
        do {
            foundOnLayer = false;
            int samples = count.sample(random);
            for (int i = 0; i < samples; i++) {
                int x = random.nextInt(16) + startX;
                int z = random.nextInt(16) + startZ;
                int y = context.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int targetY = findOnGroundYPosition(context, x, y, z, layer, pos);

                if (targetY != Integer.MAX_VALUE) {
                    output.add(BlockPos.asLong(x, targetY, z));
                    foundOnLayer = true;
                }
            }
            layer++;
        } while (foundOnLayer);
    }

    private static int findOnGroundYPosition(PlacementContext context, int x, int y, int z, int targetLayer, BlockPos.MutableBlockPos pos) {
        pos.set(x, y, z);
        int currentLayer = 0;
        BlockState currentState = context.getBlockState(pos);

        for (int currentY = y; currentY >= context.getMinBuildHeight() + 1; currentY--) {
            pos.setY(currentY - 1);
            BlockState nextState = context.getBlockState(pos);

            if (!isEmpty(nextState) && isEmpty(currentState) && !nextState.is(Blocks.BEDROCK)) {
                if (currentLayer == targetLayer) {
                    return pos.getY() + 1;
                }
                currentLayer++;
            }
            currentState = nextState;
        }

        return Integer.MAX_VALUE;
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    private static void applyFastModifier(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        try {
            GA$PlacementModifierExtension.get(modifier).generatePositionsRaw(context, random, packedPos, output);
        } catch (MethodNotImplementedException ignored) {
            fillVanillaModifier(modifier, context, random, packedPos, opIndex, output, scratch);
        }
    }

    private static boolean executeVanillaFallback(FeatureProgram program, PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        FeatureVmMetrics.recordFallbackOpExecution();
        BlockPos.MutableBlockPos input = scratch.mutablePos(opIndex).set(packedPos);
        try (Stream<BlockPos> positions = modifier.getPositions(context, random, input)) {
            Iterator<BlockPos> iterator = positions.iterator();
            boolean success = false;
            while (iterator.hasNext()) {
                if (executeDepthFirst(program, context, random, iterator.next().asLong(), opIndex + 1, scratch)) {
                    success = true;
                }
            }
            return success;
        }
    }

    private static void fillVanillaModifier(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        FeatureVmMetrics.recordFallbackOpExecution();
        BlockPos.MutableBlockPos input = scratch.mutablePos(opIndex).set(packedPos);
        try (Stream<BlockPos> positions = modifier.getPositions(context, random, input)) {
            positions.forEach(pos -> output.add(pos.asLong()));
        }
    }

    private static boolean placeFeature(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos) {
        ConfiguredFeature<?, ?> configuredFeature = program.feature().value();
        boolean success = false;
        BlockPos pos = BlockPos.of(packedPos);

        FeatureVmMetrics.recordFeaturePlaceCall();
        if (FeaturePlacementCompat.beforePlace(program.feature(), context, random, pos.mutable())) {
            success = true;
        }
        if (configuredFeature.place(context.getLevel(), context.generator(), random, pos)) {
            success = true;
        }

        return success;
    }
}
