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
import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

public final class FeatureVm {
    private static final ThreadLocal<FeatureScratchStack> SCRATCH_STACK = ThreadLocal.withInitial(FeatureScratchStack::new);
    private static final long NO_POSITION = Long.MIN_VALUE;

    private FeatureVm() {
    }

    public static boolean execute(FeatureProgram program, PlacementContext context, RandomSource random, BlockPos startPos) {
        long startNanos = FeatureVmMetrics.ENABLED ? System.nanoTime() : 0L;
        FeatureVmMetrics.recordProgramExecution();
        FeatureScratchStack stack = SCRATCH_STACK.get();
        FeatureScratch scratch = stack.acquire();

        try {
            if (!program.hasFallback()) {
                int specializedExecutor = program.specializedExecutor();
                if (specializedExecutor != FeatureExecutorKind.GENERIC) {
                    return executeSpecialized(program, context, random, startPos.asLong(), scratch, specializedExecutor);
                }
                if (program.linearFastOnly()) {
                    return executeLinearFast(program, context, random, startPos.asLong(), scratch);
                }
                return executeBufferFast(program, context, random, startPos.asLong(), scratch);
            }
            return executeDepthFirst(program, context, random, startPos.asLong(), 0, scratch);
        } finally {
            if (FeatureVmMetrics.ENABLED) {
                FeatureVmMetrics.recordExecutionNanos(System.nanoTime() - startNanos);
            }
            stack.release(scratch);
        }
    }

    private static boolean executeDepthFirst(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        if (opIndex >= program.opCount()) {
            return placeFeature(program, context, random, packedPos, opIndex, scratch);
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

    private static boolean executeLinearFast(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, FeatureScratch scratch) {
        FeatureVmMetrics.recordLinearFastExecution();
        int opCount = program.opCount();
        int executedOps = 0;
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        for (int opIndex = 0; opIndex < opCount; opIndex++) {
            executedOps++;
            packedPos = applyLinearOpcode(program.opcode(opIndex), program.modifier(opIndex), context, random, x, y, z, opIndex, scratch);
            if (packedPos == NO_POSITION) {
                FeatureVmMetrics.recordFastOpExecutions(executedOps);
                return false;
            }
            x = BlockPos.getX(packedPos);
            y = BlockPos.getY(packedPos);
            z = BlockPos.getZ(packedPos);
        }
        FeatureVmMetrics.recordFastOpExecutions(executedOps);
        return placeFeature(program, context, random, packedPos, opCount, scratch);
    }

    private static boolean executeBufferFast(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        FeatureVmMetrics.recordBufferFastExecution();
        LongScratchBuffer current = scratch.buffer(0);
        LongScratchBuffer next = scratch.buffer(1);
        current.add(startPackedPos);

        int opCount = program.opCount();
        long executedOps = 0L;
        for (int opIndex = 0; opIndex < opCount; opIndex++) {
            if (current.isEmpty()) {
                FeatureVmMetrics.recordFastOpExecutions(executedOps);
                return false;
            }
            next.clear();
            int opcode = program.opcode(opIndex);
            PlacementModifier modifier = program.modifier(opIndex);

            for (int i = 0, size = current.size(); i < size; i++) {
                applyOpcode(opcode, modifier, context, random, current.getLong(i), opIndex, next, scratch);
            }
            executedOps += current.size();

            LongScratchBuffer temp = current;
            current = next;
            next = temp;
        }
        FeatureVmMetrics.recordFastOpExecutions(executedOps);

        boolean success = false;
        int finalPosDepth = opCount + 1;
        int size = current.size();
        FeatureVmMetrics.recordFeaturePlaceCalls(size);
        for (int i = 0; i < size; i++) {
            if (placeFeatureUntracked(program, context, random, current.getLong(i), finalPosDepth, scratch)) {
                success = true;
            }
        }
        return success;
    }

    private static boolean executeSpecialized(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch, int executor) {
        return switch (executor) {
            case FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHT_RANGE ->
                    executeRepeatingInSquareHeightRange(program, context, random, startPackedPos, scratch);
            case FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHTMAP ->
                    executeRepeatingInSquareHeightmap(program, context, random, startPackedPos, scratch);
            case FeatureExecutorKind.REPEATING_IN_SQUARE_LINEAR_TAIL ->
                    executeRepeatingInSquareLinearTail(program, context, random, startPackedPos, scratch);
            default -> executeBufferFast(program, context, random, startPackedPos, scratch);
        };
    }

    private static boolean executeRepeatingInSquareLinearTail(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        BlockPos.MutableBlockPos startPos = scratch.mutablePos(0).set(startPackedPos);
        int count = ((GA$RepeatingPlacementAccess) program.modifier(0)).ga$repeatingCount(random, startPos);
        if (count <= 0) {
            return false;
        }

        boolean success = false;
        int baseX = BlockPos.getX(startPackedPos);
        int baseY = BlockPos.getY(startPackedPos);
        int baseZ = BlockPos.getZ(startPackedPos);
        int opCount = program.opCount();
        int placeAttempts = 0;

        FeatureVmMetrics.recordFastOpExecutions((long) count * opCount);
        for (int i = 0; i < count; i++) {
            long packedPos = BlockPos.asLong(baseX + random.nextInt(16), baseY, baseZ + random.nextInt(16));
            packedPos = applySpecializedLinearTail(program, context, random, packedPos, 2, scratch);
            if (packedPos != NO_POSITION) {
                placeAttempts++;
                if (placeFeatureUntracked(program, context, random, packedPos, opCount + 1, scratch)) {
                    success = true;
                }
            }
        }
        FeatureVmMetrics.recordFeaturePlaceCalls(placeAttempts);
        return success;
    }

    private static boolean executeRepeatingInSquareHeightRange(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        BlockPos.MutableBlockPos startPos = scratch.mutablePos(0).set(startPackedPos);
        int count = ((GA$RepeatingPlacementAccess) program.modifier(0)).ga$repeatingCount(random, startPos);
        if (count <= 0) {
            return false;
        }

        GA$HeightRangePlacementAccess height = (GA$HeightRangePlacementAccess) program.modifier(2);
        boolean success = false;
        int baseX = BlockPos.getX(startPackedPos);
        int baseZ = BlockPos.getZ(startPackedPos);
        int opCount = program.opCount();

        FeatureVmMetrics.recordFastOpExecutions((long) count * opCount);
        int placeAttempts = 0;
        for (int i = 0; i < count; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            long packedPos = BlockPos.asLong(x, height.ga$heightProvider().sample(random, context), z);
            packedPos = applySpecializedLinearTail(program, context, random, packedPos, 3, scratch);
            if (packedPos != NO_POSITION) {
                placeAttempts++;
                if (placeFeatureUntracked(program, context, random, packedPos, opCount + 1, scratch)) {
                    success = true;
                }
            }
        }
        FeatureVmMetrics.recordFeaturePlaceCalls(placeAttempts);
        return success;
    }

    private static boolean executeRepeatingInSquareHeightmap(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        BlockPos.MutableBlockPos startPos = scratch.mutablePos(0).set(startPackedPos);
        int count = ((GA$RepeatingPlacementAccess) program.modifier(0)).ga$repeatingCount(random, startPos);
        if (count <= 0) {
            return false;
        }

        GA$HeightmapPlacementAccess heightmap = (GA$HeightmapPlacementAccess) program.modifier(2);
        Heightmap.Types type = heightmap.ga$heightmapType();
        int minBuildHeight = context.getMinBuildHeight();
        boolean success = false;
        int baseX = BlockPos.getX(startPackedPos);
        int baseZ = BlockPos.getZ(startPackedPos);
        int opCount = program.opCount();

        FeatureVmMetrics.recordFastOpExecutions((long) count * opCount);
        int placeAttempts = 0;
        for (int i = 0; i < count; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            int y = fastHeight(context, type, x, z);
            if (y <= minBuildHeight) {
                continue;
            }

            long packedPos = BlockPos.asLong(x, y, z);
            packedPos = applySpecializedLinearTail(program, context, random, packedPos, 3, scratch);
            if (packedPos != NO_POSITION) {
                placeAttempts++;
                if (placeFeatureUntracked(program, context, random, packedPos, opCount + 1, scratch)) {
                    success = true;
                }
            }
        }
        FeatureVmMetrics.recordFeaturePlaceCalls(placeAttempts);
        return success;
    }

    private static long applySpecializedLinearTail(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int startOpIndex, FeatureScratch scratch) {
        for (int opIndex = startOpIndex, opCount = program.opCount(); opIndex < opCount; opIndex++) {
            packedPos = applyLinearOpcode(program.opcode(opIndex), program.modifier(opIndex), context, random, packedPos, opIndex, scratch);
            if (packedPos == NO_POSITION) {
                return NO_POSITION;
            }
        }
        return packedPos;
    }

    private static long applyLinearOpcode(int opcode, PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        return applyLinearOpcode(opcode, modifier, context, random, BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos), opIndex, scratch);
    }

    private static long applyLinearOpcode(int opcode, PlacementModifier modifier, PlacementContext context, RandomSource random, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        return switch (opcode) {
            case FeatureOpcode.IN_SQUARE -> applyInSquareLinear(random, x, y, z);
            case FeatureOpcode.HEIGHT_RANGE -> applyHeightRangeLinear(modifier, context, random, x, z);
            case FeatureOpcode.HEIGHTMAP -> applyHeightmapLinear(modifier, context, x, z);
            case FeatureOpcode.RANDOM_OFFSET -> applyRandomOffsetLinear(modifier, random, x, y, z);
            case FeatureOpcode.PLACEMENT_FILTER -> applyPlacementFilterLinear(modifier, context, random, x, y, z, opIndex, scratch);
            case FeatureOpcode.ENVIRONMENT_SCAN -> applyEnvironmentScanLinear(modifier, context, x, y, z, opIndex, scratch);
            default -> {
                LongScratchBuffer output = scratch.buffer(opIndex);
                applyOpcode(opcode, modifier, context, random, BlockPos.asLong(x, y, z), opIndex, output, scratch);
                yield output.size() == 1 ? output.getLong(0) : NO_POSITION;
            }
        };
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

    private static long applyInSquareLinear(RandomSource random, long packedPos) {
        int x = BlockPos.getX(packedPos) + random.nextInt(16);
        int z = BlockPos.getZ(packedPos) + random.nextInt(16);
        return BlockPos.asLong(x, BlockPos.getY(packedPos), z);
    }

    private static long applyInSquareLinear(RandomSource random, int x, int y, int z) {
        return BlockPos.asLong(x + random.nextInt(16), y, z + random.nextInt(16));
    }

    private static void applyHeightRange(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        GA$HeightRangePlacementAccess access = (GA$HeightRangePlacementAccess) modifier;
        output.add(BlockPos.asLong(BlockPos.getX(packedPos), access.ga$heightProvider().sample(random, context), BlockPos.getZ(packedPos)));
    }

    private static long applyHeightRangeLinear(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos) {
        GA$HeightRangePlacementAccess access = (GA$HeightRangePlacementAccess) modifier;
        return BlockPos.asLong(BlockPos.getX(packedPos), access.ga$heightProvider().sample(random, context), BlockPos.getZ(packedPos));
    }

    private static long applyHeightRangeLinear(PlacementModifier modifier, PlacementContext context, RandomSource random, int x, int z) {
        GA$HeightRangePlacementAccess access = (GA$HeightRangePlacementAccess) modifier;
        return BlockPos.asLong(x, access.ga$heightProvider().sample(random, context), z);
    }

    private static void applyHeightmap(PlacementModifier modifier, PlacementContext context, long packedPos, LongScratchBuffer output) {
        GA$HeightmapPlacementAccess access = (GA$HeightmapPlacementAccess) modifier;
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int y = fastHeight(context, access.ga$heightmapType(), x, z);
        if (y > context.getMinBuildHeight()) {
            output.add(BlockPos.asLong(x, y, z));
        }
    }

    private static long applyHeightmapLinear(PlacementModifier modifier, PlacementContext context, long packedPos) {
        GA$HeightmapPlacementAccess access = (GA$HeightmapPlacementAccess) modifier;
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int y = fastHeight(context, access.ga$heightmapType(), x, z);
        return y > context.getMinBuildHeight() ? BlockPos.asLong(x, y, z) : NO_POSITION;
    }

    private static long applyHeightmapLinear(PlacementModifier modifier, PlacementContext context, int x, int z) {
        GA$HeightmapPlacementAccess access = (GA$HeightmapPlacementAccess) modifier;
        int y = fastHeight(context, access.ga$heightmapType(), x, z);
        return y > context.getMinBuildHeight() ? BlockPos.asLong(x, y, z) : NO_POSITION;
    }

    private static void applyRandomOffset(PlacementModifier modifier, RandomSource random, long packedPos, LongScratchBuffer output) {
        GA$RandomOffsetPlacementAccess access = (GA$RandomOffsetPlacementAccess) modifier;
        int x = BlockPos.getX(packedPos) + access.ga$xzSpread().sample(random);
        int y = BlockPos.getY(packedPos) + access.ga$ySpread().sample(random);
        int z = BlockPos.getZ(packedPos) + access.ga$xzSpread().sample(random);
        output.add(BlockPos.asLong(x, y, z));
    }

    private static long applyRandomOffsetLinear(PlacementModifier modifier, RandomSource random, long packedPos) {
        GA$RandomOffsetPlacementAccess access = (GA$RandomOffsetPlacementAccess) modifier;
        int x = BlockPos.getX(packedPos) + access.ga$xzSpread().sample(random);
        int y = BlockPos.getY(packedPos) + access.ga$ySpread().sample(random);
        int z = BlockPos.getZ(packedPos) + access.ga$xzSpread().sample(random);
        return BlockPos.asLong(x, y, z);
    }

    private static long applyRandomOffsetLinear(PlacementModifier modifier, RandomSource random, int x, int y, int z) {
        GA$RandomOffsetPlacementAccess access = (GA$RandomOffsetPlacementAccess) modifier;
        return BlockPos.asLong(x + access.ga$xzSpread().sample(random), y + access.ga$ySpread().sample(random), z + access.ga$xzSpread().sample(random));
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

    private static long applyPlacementFilterLinear(PlacementModifier modifier, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(packedPos);
        return ((GA$PlacementFilterAccess) modifier).ga$shouldPlace(context, random, pos) ? packedPos : NO_POSITION;
    }

    private static long applyPlacementFilterLinear(PlacementModifier modifier, PlacementContext context, RandomSource random, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        return ((GA$PlacementFilterAccess) modifier).ga$shouldPlaceRaw(context, random, x, y, z, scratch.mutablePos(opIndex)) ? BlockPos.asLong(x, y, z) : NO_POSITION;
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

    private static long applyEnvironmentScanLinear(PlacementModifier modifier, PlacementContext context, long packedPos, int opIndex, FeatureScratch scratch) {
        return applyEnvironmentScanLinear(modifier, context, BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos), opIndex, scratch);
    }

    private static long applyEnvironmentScanLinear(PlacementModifier modifier, PlacementContext context, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        GA$EnvironmentScanPlacementAccess access = (GA$EnvironmentScanPlacementAccess) modifier;
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(x, y, z);
        WorldGenLevel level = context.getLevel();

        if (!access.ga$allowedSearchCondition().test(level, pos)) {
            return NO_POSITION;
        }

        Direction direction = access.ga$directionOfSearch();
        int stepX = direction.getStepX();
        int stepY = direction.getStepY();
        int stepZ = direction.getStepZ();

        for (int i = 0; i < access.ga$maxSteps(); i++) {
            if (access.ga$targetCondition().test(level, pos)) {
                return pos.asLong();
            }

            x += stepX;
            y += stepY;
            z += stepZ;
            pos.set(x, y, z);

            if (level.isOutsideBuildHeight(y)) {
                return NO_POSITION;
            }

            if (!access.ga$allowedSearchCondition().test(level, pos)) {
                break;
            }
        }

        return access.ga$targetCondition().test(level, pos) ? pos.asLong() : NO_POSITION;
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

    private static int fastHeight(PlacementContext context, Heightmap.Types type, int x, int z) {
        try {
            ChunkAccess chunk = context.getLevel().getChunk(x >> 4, z >> 4);
            Heightmap heightmap = ((ChunkAccess$getOrCreateHeightmapUnsynchronized) chunk).bts$getOrCreateHeightmapUnsynchronized(type);
            if (heightmap != null) {
                return heightmap.getFirstAvailable(x & 15, z & 15);
            }
        } catch (RuntimeException ignored) {
            // Some modded worldgen contexts may virtualize chunk access; keep vanilla as a safe fallback.
        }
        return context.getHeight(type, x, z);
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

    private static boolean placeFeature(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        FeatureVmMetrics.recordFeaturePlaceCall();
        return placeFeatureUntracked(program, context, random, packedPos, opIndex, scratch);
    }

    private static boolean placeFeatureUntracked(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        ConfiguredFeature<?, ?> configuredFeature = program.feature().value();
        boolean success = false;
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(packedPos);

        if (FeaturePlacementCompat.enabled() && FeaturePlacementCompat.beforePlace(program.feature(), context, random, scratch.mutablePos(opIndex + 1).set(packedPos))) {
            success = true;
        }
        if (configuredFeature.place(context.getLevel(), context.generator(), random, pos)) {
            success = true;
        }

        return success;
    }
}
