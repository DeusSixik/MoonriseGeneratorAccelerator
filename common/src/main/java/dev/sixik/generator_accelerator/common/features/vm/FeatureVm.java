package dev.sixik.generator_accelerator.common.features.vm;

import dev.sixik.generator_accelerator.api.exceptions.MethodNotImplementedException;
import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskExtension;
import dev.sixik.generator_accelerator.api.patches.GA$EnvironmentScanPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementFilterAccess;
import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;

public final class FeatureVm {
    private static final ThreadLocal<FeatureScratchStack> SCRATCH_STACK = ThreadLocal.withInitial(FeatureScratchStack::new);
    private static final long NO_POSITION = Long.MIN_VALUE;
    private static final boolean FEATURE_PLACEMENT_COMPAT = FeaturePlacementCompat.enabled();

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
                    return executeLinearFast(program, context, random, startPos.getX(), startPos.getY(), startPos.getZ(), scratch);
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
        if (opcode == FeatureOpcode.VANILLA_FALLBACK) {
            return executeVanillaFallback(program, context, random, packedPos, opIndex, scratch);
        }

        FeatureVmMetrics.recordFastOpExecution();
        LongScratchBuffer output = scratch.buffer(opIndex);
        applyOpcode(program, opcode, context, random, packedPos, opIndex, output, scratch);

        boolean success = false;
        long[] positions = output.elements();
        for (int i = 0, size = output.size(); i < size; i++) {
            if (executeDepthFirst(program, context, random, positions[i], opIndex + 1, scratch)) {
                success = true;
            }
        }
        return success;
    }

    private static boolean executeLinearFast(FeatureProgram program, PlacementContext context, RandomSource random, int x, int y, int z, FeatureScratch scratch) {
        FeatureVmMetrics.recordLinearFastExecution();
        int opCount = program.opCount();
        int[] opcodes = program.opcodes();
        int executedOps = 0;
        for (int opIndex = 0; opIndex < opCount; opIndex++) {
            executedOps++;
            switch (opcodes[opIndex]) {
                case FeatureOpcode.IN_SQUARE -> {
                    x += random.nextInt(16);
                    z += random.nextInt(16);
                }
                case FeatureOpcode.HEIGHT_RANGE -> y = program.heightProvider(opIndex).sample(random, context);
                case FeatureOpcode.HEIGHTMAP -> {
                    y = fastHeight(context, program.heightmapType(opIndex), x, z);
                    if (y <= context.getMinBuildHeight()) {
                        FeatureVmMetrics.recordFastOpExecutions(executedOps);
                        return false;
                    }
                }
                case FeatureOpcode.RANDOM_OFFSET -> {
                    FeatureProgram.RandomOffsetData data = program.randomOffset(opIndex);
                    x += data.xzSpread().sample(random);
                    y += data.ySpread().sample(random);
                    z += data.xzSpread().sample(random);
                }
                case FeatureOpcode.PLACEMENT_FILTER, FeatureOpcode.BIOME_FILTER -> {
                    if (!passesPlacementFilter(program, opcodes[opIndex], context, random, x, y, z, opIndex, scratch)) {
                        FeatureVmMetrics.recordFastOpExecutions(executedOps);
                        return false;
                    }
                }
                case FeatureOpcode.ENVIRONMENT_SCAN -> {
                    long scanned = applyEnvironmentScanLinear(program, context, x, y, z, opIndex, scratch);
                    if (scanned == NO_POSITION) {
                        FeatureVmMetrics.recordFastOpExecutions(executedOps);
                        return false;
                    }
                    x = BlockPos.getX(scanned);
                    y = BlockPos.getY(scanned);
                    z = BlockPos.getZ(scanned);
                }
                default -> {
                    long packedPos = applyLinearOpcode(program, opcodes[opIndex], context, random, x, y, z, opIndex, scratch);
                    if (packedPos == NO_POSITION) {
                        FeatureVmMetrics.recordFastOpExecutions(executedOps);
                        return false;
                    }
                    x = BlockPos.getX(packedPos);
                    y = BlockPos.getY(packedPos);
                    z = BlockPos.getZ(packedPos);
                }
            }
        }
        FeatureVmMetrics.recordFastOpExecutions(executedOps);
        FeatureVmMetrics.recordFeaturePlaceCall();
        return placeFeatureAt(program, context, random, x, y, z, opCount, scratch);
    }

    private static boolean executeBufferFast(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        FeatureVmMetrics.recordBufferFastExecution();
        LongScratchBuffer current = scratch.buffer(0);
        LongScratchBuffer next = scratch.buffer(1);
        current.add(startPackedPos);

        int opCount = program.opCount();
        int[] opcodes = program.opcodes();
        long executedOps = 0L;
        for (int opIndex = 0; opIndex < opCount; opIndex++) {
            if (current.isEmpty()) {
                FeatureVmMetrics.recordFastOpExecutions(executedOps);
                return false;
            }
            next.clear();
            int opcode = opcodes[opIndex];

            long[] currentValues = current.elements();
            for (int i = 0, size = current.size(); i < size; i++) {
                applyOpcode(program, opcode, context, random, currentValues[i], opIndex, next, scratch);
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
        long[] finalPositions = current.elements();
        for (int i = 0; i < size; i++) {
            if (placeFeatureUntracked(program, context, random, finalPositions[i], finalPosDepth, scratch)) {
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
            case FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHT_RANGE_FILTER ->
                    executeRepeatingInSquareHeightRangeFilter(program, context, random, startPackedPos, scratch);
            case FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHTMAP_FILTER ->
                    executeRepeatingInSquareHeightmapFilter(program, context, random, startPackedPos, scratch);
            default -> executeBufferFast(program, context, random, startPackedPos, scratch);
        };
    }

    private static boolean executeRepeatingInSquareLinearTail(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        BlockPos.MutableBlockPos startPos = scratch.mutablePos(0).set(startPackedPos);
        int count = program.repeatingPlacement(0).ga$repeatingCount(random, startPos);
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
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            long packedPos = applySpecializedLinearTail(program, context, random, x, baseY, z, 2, scratch);
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
        int count = program.repeatingPlacement(0).ga$repeatingCount(random, startPos);
        if (count <= 0) {
            return false;
        }

        var heightProvider = program.heightProvider(2);
        boolean success = false;
        int baseX = BlockPos.getX(startPackedPos);
        int baseZ = BlockPos.getZ(startPackedPos);
        int opCount = program.opCount();

        FeatureVmMetrics.recordFastOpExecutions((long) count * opCount);
        int placeAttempts = 0;
        for (int i = 0; i < count; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            int y = heightProvider.sample(random, context);
            if (opCount == 3) {
                placeAttempts++;
                if (placeFeatureAt(program, context, random, x, y, z, opCount + 1, scratch)) {
                    success = true;
                }
                continue;
            }

            long packedPos = applySpecializedLinearTail(program, context, random, x, y, z, 3, scratch);
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
        int count = program.repeatingPlacement(0).ga$repeatingCount(random, startPos);
        if (count <= 0) {
            return false;
        }

        Heightmap.Types type = program.heightmapType(2);
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

            if (opCount == 3) {
                placeAttempts++;
                if (placeFeatureAt(program, context, random, x, y, z, opCount + 1, scratch)) {
                    success = true;
                }
                continue;
            }

            long packedPos = applySpecializedLinearTail(program, context, random, x, y, z, 3, scratch);
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

    private static boolean executeRepeatingInSquareHeightRangeFilter(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        BlockPos.MutableBlockPos startPos = scratch.mutablePos(0).set(startPackedPos);
        int count = program.repeatingPlacement(0).ga$repeatingCount(random, startPos);
        if (count <= 0) {
            return false;
        }

        var heightProvider = program.heightProvider(2);
        int filterOpcode = program.opcode(3);
        int baseX = BlockPos.getX(startPackedPos);
        int baseZ = BlockPos.getZ(startPackedPos);
        boolean success = false;
        int placeAttempts = 0;

        FeatureVmMetrics.recordFastOpExecutions((long) count * program.opCount());
        for (int i = 0; i < count; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            int y = heightProvider.sample(random, context);
            if (passesPlacementFilter(program, filterOpcode, context, random, x, y, z, 3, scratch)) {
                placeAttempts++;
                if (placeFeatureAt(program, context, random, x, y, z, 5, scratch)) {
                    success = true;
                }
            }
        }
        FeatureVmMetrics.recordFeaturePlaceCalls(placeAttempts);
        return success;
    }

    private static boolean executeRepeatingInSquareHeightmapFilter(FeatureProgram program, PlacementContext context, RandomSource random, long startPackedPos, FeatureScratch scratch) {
        BlockPos.MutableBlockPos startPos = scratch.mutablePos(0).set(startPackedPos);
        int count = program.repeatingPlacement(0).ga$repeatingCount(random, startPos);
        if (count <= 0) {
            return false;
        }

        Heightmap.Types type = program.heightmapType(2);
        int filterOpcode = program.opcode(3);
        int minBuildHeight = context.getMinBuildHeight();
        int baseX = BlockPos.getX(startPackedPos);
        int baseZ = BlockPos.getZ(startPackedPos);
        boolean success = false;
        int placeAttempts = 0;

        FeatureVmMetrics.recordFastOpExecutions((long) count * program.opCount());
        for (int i = 0; i < count; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            int y = fastHeight(context, type, x, z);
            if (y > minBuildHeight && passesPlacementFilter(program, filterOpcode, context, random, x, y, z, 3, scratch)) {
                placeAttempts++;
                if (placeFeatureAt(program, context, random, x, y, z, 5, scratch)) {
                    success = true;
                }
            }
        }
        FeatureVmMetrics.recordFeaturePlaceCalls(placeAttempts);
        return success;
    }

    private static long applySpecializedLinearTail(FeatureProgram program, PlacementContext context, RandomSource random, int x, int y, int z, int startOpIndex, FeatureScratch scratch) {
        int[] opcodes = program.opcodes();
        for (int opIndex = startOpIndex, opCount = opcodes.length; opIndex < opCount; opIndex++) {
            switch (opcodes[opIndex]) {
                case FeatureOpcode.IN_SQUARE -> {
                    x += random.nextInt(16);
                    z += random.nextInt(16);
                }
                case FeatureOpcode.HEIGHT_RANGE -> y = program.heightProvider(opIndex).sample(random, context);
                case FeatureOpcode.HEIGHTMAP -> {
                    y = fastHeight(context, program.heightmapType(opIndex), x, z);
                    if (y <= context.getMinBuildHeight()) {
                        return NO_POSITION;
                    }
                }
                case FeatureOpcode.RANDOM_OFFSET -> {
                    FeatureProgram.RandomOffsetData data = program.randomOffset(opIndex);
                    x += data.xzSpread().sample(random);
                    y += data.ySpread().sample(random);
                    z += data.xzSpread().sample(random);
                }
                case FeatureOpcode.PLACEMENT_FILTER, FeatureOpcode.BIOME_FILTER -> {
                    if (!passesPlacementFilter(program, opcodes[opIndex], context, random, x, y, z, opIndex, scratch)) {
                        return NO_POSITION;
                    }
                }
                case FeatureOpcode.ENVIRONMENT_SCAN -> {
                    long scanned = applyEnvironmentScanLinear(program, context, x, y, z, opIndex, scratch);
                    if (scanned == NO_POSITION) {
                        return NO_POSITION;
                    }
                    x = BlockPos.getX(scanned);
                    y = BlockPos.getY(scanned);
                    z = BlockPos.getZ(scanned);
                }
                default -> {
                    long packedPos = applyLinearOpcode(program, opcodes[opIndex], context, random, x, y, z, opIndex, scratch);
                    if (packedPos == NO_POSITION) {
                        return NO_POSITION;
                    }
                    x = BlockPos.getX(packedPos);
                    y = BlockPos.getY(packedPos);
                    z = BlockPos.getZ(packedPos);
                }
            }
        }
        return BlockPos.asLong(x, y, z);
    }

    private static long applyLinearOpcode(FeatureProgram program, int opcode, PlacementContext context, RandomSource random, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        return switch (opcode) {
            case FeatureOpcode.IN_SQUARE -> applyInSquareLinear(random, x, y, z);
            case FeatureOpcode.HEIGHT_RANGE -> applyHeightRangeLinear(program, context, random, x, z, opIndex);
            case FeatureOpcode.HEIGHTMAP -> applyHeightmapLinear(program, context, x, z, opIndex);
            case FeatureOpcode.RANDOM_OFFSET -> applyRandomOffsetLinear(program, random, x, y, z, opIndex);
            case FeatureOpcode.PLACEMENT_FILTER -> applyPlacementFilterLinear(program, context, random, x, y, z, opIndex, scratch);
            case FeatureOpcode.BIOME_FILTER -> passesBiomeFilter(context, x, y, z, opIndex, scratch) ? BlockPos.asLong(x, y, z) : NO_POSITION;
            case FeatureOpcode.ENVIRONMENT_SCAN -> applyEnvironmentScanLinear(program, context, x, y, z, opIndex, scratch);
            default -> {
                LongScratchBuffer output = scratch.buffer(opIndex);
                applyOpcode(program, opcode, context, random, BlockPos.asLong(x, y, z), opIndex, output, scratch);
                yield output.size() == 1 ? output.getLong(0) : NO_POSITION;
            }
        };
    }

    private static void applyOpcode(FeatureProgram program, int opcode, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        switch (opcode) {
            case FeatureOpcode.IN_SQUARE -> applyInSquare(random, packedPos, output);
            case FeatureOpcode.HEIGHT_RANGE -> applyHeightRange(program, context, random, packedPos, opIndex, output);
            case FeatureOpcode.HEIGHTMAP -> applyHeightmap(program, context, packedPos, opIndex, output);
            case FeatureOpcode.RANDOM_OFFSET -> applyRandomOffset(program, random, packedPos, opIndex, output);
            case FeatureOpcode.REPEATING -> applyRepeating(program, random, packedPos, opIndex, output, scratch);
            case FeatureOpcode.PLACEMENT_FILTER -> applyPlacementFilter(program, context, random, packedPos, opIndex, output, scratch);
            case FeatureOpcode.BIOME_FILTER -> applyBiomeFilter(context, packedPos, opIndex, output, scratch);
            case FeatureOpcode.FIXED -> applyFixed(program, packedPos, opIndex, output);
            case FeatureOpcode.CARVING_MASK -> applyCarvingMask(program, context, packedPos, opIndex, output);
            case FeatureOpcode.ENVIRONMENT_SCAN -> applyEnvironmentScan(program, context, packedPos, opIndex, output, scratch);
            case FeatureOpcode.COUNT_ON_EVERY_LAYER -> applyCountOnEveryLayer(program, context, random, packedPos, opIndex, output, scratch);
            case FeatureOpcode.RAW_MODIFIER -> applyFastModifier(program, context, random, packedPos, opIndex, output, scratch);
            default -> fillVanillaModifier(program, context, random, packedPos, opIndex, output, scratch);
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

    private static void applyHeightRange(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output) {
        output.add(BlockPos.asLong(BlockPos.getX(packedPos), program.heightProvider(opIndex).sample(random, context), BlockPos.getZ(packedPos)));
    }

    private static long applyHeightRangeLinear(FeatureProgram program, PlacementContext context, RandomSource random, int x, int z, int opIndex) {
        return BlockPos.asLong(x, program.heightProvider(opIndex).sample(random, context), z);
    }

    private static void applyHeightmap(FeatureProgram program, PlacementContext context, long packedPos, int opIndex, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int y = fastHeight(context, program.heightmapType(opIndex), x, z);
        if (y > context.getMinBuildHeight()) {
            output.add(BlockPos.asLong(x, y, z));
        }
    }

    private static long applyHeightmapLinear(FeatureProgram program, PlacementContext context, int x, int z, int opIndex) {
        int y = fastHeight(context, program.heightmapType(opIndex), x, z);
        return y > context.getMinBuildHeight() ? BlockPos.asLong(x, y, z) : NO_POSITION;
    }

    private static void applyRandomOffset(FeatureProgram program, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output) {
        FeatureProgram.RandomOffsetData data = program.randomOffset(opIndex);
        int x = BlockPos.getX(packedPos) + data.xzSpread().sample(random);
        int y = BlockPos.getY(packedPos) + data.ySpread().sample(random);
        int z = BlockPos.getZ(packedPos) + data.xzSpread().sample(random);
        output.add(BlockPos.asLong(x, y, z));
    }

    private static long applyRandomOffsetLinear(FeatureProgram program, RandomSource random, int x, int y, int z, int opIndex) {
        FeatureProgram.RandomOffsetData data = program.randomOffset(opIndex);
        return BlockPos.asLong(x + data.xzSpread().sample(random), y + data.ySpread().sample(random), z + data.xzSpread().sample(random));
    }

    private static void applyRepeating(FeatureProgram program, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(packedPos);
        int count = program.repeatingPlacement(opIndex).ga$repeatingCount(random, pos);
        output.addRepeated(packedPos, count);
    }

    private static void applyPlacementFilter(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(packedPos);
        if (program.placementFilter(opIndex).ga$shouldPlace(context, random, pos)) {
            output.add(packedPos);
        }
    }

    private static long applyPlacementFilterLinear(FeatureProgram program, PlacementContext context, RandomSource random, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        return passesPlacementFilter(program, FeatureOpcode.PLACEMENT_FILTER, context, random, x, y, z, opIndex, scratch) ? BlockPos.asLong(x, y, z) : NO_POSITION;
    }

    private static boolean passesPlacementFilter(FeatureProgram program, int opcode, PlacementContext context, RandomSource random, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        if (opcode == FeatureOpcode.BIOME_FILTER) {
            return passesBiomeFilter(context, x, y, z, opIndex, scratch);
        }
        return program.placementFilter(opIndex).ga$shouldPlaceRaw(context, random, x, y, z, scratch.mutablePos(opIndex));
    }

    private static void applyBiomeFilter(PlacementContext context, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        if (passesBiomeFilter(context, x, y, z, opIndex, scratch)) {
            output.add(packedPos);
        }
    }

    private static boolean passesBiomeFilter(PlacementContext context, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        Optional<PlacedFeature> topFeature = context.topFeature();
        if (topFeature.isEmpty()) {
            throw new IllegalStateException("Tried to biome check an unregistered feature, or a feature that should not restrict the biome");
        }

        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(x, y, z);
        Holder<Biome> biome = context.getLevel().getBiome(pos);
        return scratch.biomeFilterScratch().hasFeature(context.generator(), biome, topFeature.get());
    }

    private static void applyFixed(FeatureProgram program, long packedPos, int opIndex, LongScratchBuffer output) {
        int chunkX = BlockPos.getX(packedPos) >> 4;
        int chunkZ = BlockPos.getZ(packedPos) >> 4;
        FeatureProgram.FixedPlacementData data = program.fixedPlacement(opIndex);
        long[] positions = data.positions();
        int[] chunkXs = data.chunkXs();
        int[] chunkZs = data.chunkZs();

        for (int i = 0; i < positions.length; i++) {
            if (chunkX == chunkXs[i] && chunkZ == chunkZs[i]) {
                output.add(positions[i]);
            }
        }
    }

    private static void applyCarvingMask(FeatureProgram program, PlacementContext context, long packedPos, int opIndex, LongScratchBuffer output) {
        int bx = BlockPos.getX(packedPos);
        int bz = BlockPos.getZ(packedPos);
        ChunkPos chunkPos = new ChunkPos(bx >> 4, bz >> 4);
        CarvingMask mask = context.getCarvingMask(chunkPos, program.carvingStep(opIndex));
        if (mask != null) {
            ((GA$CarvingMaskExtension) mask).bts$addPositionsRaw(chunkPos, output);
        }
    }

    private static void applyEnvironmentScan(FeatureProgram program, PlacementContext context, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        GA$EnvironmentScanPlacementAccess access = (GA$EnvironmentScanPlacementAccess) program.modifier(opIndex);
        FeatureProgram.EnvironmentScanData data = program.environmentScan(opIndex);
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(x, y, z);
        WorldGenLevel level = context.getLevel();

        if (!access.ga$allowedSearchCondition().test(level, pos)) {
            return;
        }

        for (int i = 0; i < data.maxSteps(); i++) {
            if (access.ga$targetCondition().test(level, pos)) {
                output.add(pos.asLong());
                return;
            }

            x += data.stepX();
            y += data.stepY();
            z += data.stepZ();
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

    private static long applyEnvironmentScanLinear(FeatureProgram program, PlacementContext context, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        GA$EnvironmentScanPlacementAccess access = (GA$EnvironmentScanPlacementAccess) program.modifier(opIndex);
        FeatureProgram.EnvironmentScanData data = program.environmentScan(opIndex);
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(x, y, z);
        WorldGenLevel level = context.getLevel();

        if (!access.ga$allowedSearchCondition().test(level, pos)) {
            return NO_POSITION;
        }

        for (int i = 0; i < data.maxSteps(); i++) {
            if (access.ga$targetCondition().test(level, pos)) {
                return pos.asLong();
            }

            x += data.stepX();
            y += data.stepY();
            z += data.stepZ();
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

    private static void applyCountOnEveryLayer(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        int startX = BlockPos.getX(packedPos);
        int startZ = BlockPos.getZ(packedPos);
        IntProvider count = program.countProvider(opIndex);
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

    private static void applyFastModifier(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        try {
            program.rawModifier(opIndex).generatePositionsRaw(context, random, packedPos, output);
        } catch (MethodNotImplementedException ignored) {
            fillVanillaModifier(program, context, random, packedPos, opIndex, output, scratch);
        }
    }

    private static boolean executeVanillaFallback(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        FeatureVmMetrics.recordFallbackOpExecution();
        BlockPos.MutableBlockPos input = scratch.mutablePos(opIndex).set(packedPos);
        try (Stream<BlockPos> positions = program.modifier(opIndex).getPositions(context, random, input)) {
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

    private static void fillVanillaModifier(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, LongScratchBuffer output, FeatureScratch scratch) {
        FeatureVmMetrics.recordFallbackOpExecution();
        BlockPos.MutableBlockPos input = scratch.mutablePos(opIndex).set(packedPos);
        try (Stream<BlockPos> positions = program.modifier(opIndex).getPositions(context, random, input)) {
            Iterator<BlockPos> iterator = positions.iterator();
            while (iterator.hasNext()) {
                output.add(iterator.next().asLong());
            }
        }
    }

    private static boolean placeFeature(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        FeatureVmMetrics.recordFeaturePlaceCall();
        return placeFeatureUntracked(program, context, random, packedPos, opIndex, scratch);
    }

    private static boolean placeFeatureUntracked(FeatureProgram program, PlacementContext context, RandomSource random, long packedPos, int opIndex, FeatureScratch scratch) {
        boolean success = false;
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(packedPos);

        if (FEATURE_PLACEMENT_COMPAT && FeaturePlacementCompat.beforePlace(program.feature(), context, random, scratch.mutablePos(opIndex + 1).set(packedPos))) {
            success = true;
        }
        if (placeConfiguredFeature(program, context, random, pos, opIndex, scratch)) {
            success = true;
        }

        return success;
    }

    private static boolean placeFeatureAt(FeatureProgram program, PlacementContext context, RandomSource random, int x, int y, int z, int opIndex, FeatureScratch scratch) {
        boolean success = false;
        BlockPos.MutableBlockPos pos = scratch.mutablePos(opIndex).set(x, y, z);

        if (FEATURE_PLACEMENT_COMPAT && FeaturePlacementCompat.beforePlace(program.feature(), context, random, scratch.mutablePos(opIndex + 1).set(x, y, z))) {
            success = true;
        }
        if (placeConfiguredFeature(program, context, random, pos, opIndex, scratch)) {
            success = true;
        }

        return success;
    }

    private static boolean placeConfiguredFeature(FeatureProgram program, PlacementContext context, RandomSource random, BlockPos.MutableBlockPos pos, int opIndex, FeatureScratch scratch) {
        WorldGenLevel level = context.getLevel();
        if (!level.ensureCanWrite(pos)) {
            return false;
        }
        return program.featureImpl().place(scratch.featurePlaceContext(opIndex).set(level, context.generator(), random, pos, program.featureConfig()));
    }
}
