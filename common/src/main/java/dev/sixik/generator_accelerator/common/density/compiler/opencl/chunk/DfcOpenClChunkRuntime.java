package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClConfig;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClRuntime;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

public final class DfcOpenClChunkRuntime {
    private static final DfcOpenClChunkRuntime GLOBAL = new DfcOpenClChunkRuntime();

    public enum OutputMode {
        DENSITY,
        PACKED_BLOCKS
    }

    public record Attempt(boolean allowed, String reason) {
        public static Attempt accepted() {
            return new Attempt(true, "ok");
        }

        public static Attempt rejected(String reason) {
            return new Attempt(false, reason);
        }
    }

    public record Result(boolean present, DfcOpenClChunkResult output, String reason) {
        public static Result empty(String reason) {
            return new Result(false, null, reason);
        }
    }

    public static DfcOpenClChunkRuntime global() {
        return GLOBAL;
    }

    public boolean tryFillSingleChunk(
            Blender blender,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess chunkAccess,
            int minCellY,
            int cellCountY) {
        if (!DfcOpenClConfig.chunkNoiseEnabled()) {
            return false;
        }
        DfcOpenClChunkStats.recordCall();
        DfcOpenClChunkStats.recordSkip("no_plan");
        return false;
    }

    public Attempt preflight(DfcOpenClChunkRequest request, OutputMode mode) {
        if (!DfcOpenClConfig.chunkNoiseEnabled()) {
            return Attempt.rejected("disabled");
        }
        if (request == null || !request.validShape() || mode == null) {
            return Attempt.rejected("shape");
        }
        int bytes;
        try {
            DfcOpenClChunkOutputLayout layout = DfcOpenClChunkOutputLayout.forRequest(request);
            bytes = mode == OutputMode.DENSITY ? layout.densityOutputBytes() : layout.packedBlockOutputBytes();
        } catch (ArithmeticException | IllegalArgumentException ignored) {
            return Attempt.rejected("shape");
        }
        if (bytes > request.maxOutputBytes()) {
            return Attempt.rejected("memory");
        }
        return Attempt.accepted();
    }

    public Result tryEvaluateDensityPrototype(DfcOpenClChunkRequest request) {
        return tryEvaluateDensityPrototype(request, null);
    }

    public Result tryEvaluateDensityPrototype(DfcOpenClChunkRequest request, CompiledDensityFunction compiled) {
        DfcOpenClChunkStats.recordCall();
        Attempt attempt = preflight(request, OutputMode.DENSITY);
        if (!attempt.allowed()) {
            DfcOpenClChunkStats.recordSkip(attempt.reason());
            return Result.empty(attempt.reason());
        }
        if (compiled == null) {
            DfcOpenClChunkStats.recordSkip("no_plan");
            return Result.empty("no_plan");
        }
        DfcOpenClChunkOutputLayout layout = DfcOpenClChunkOutputLayout.forRequest(request);
        DfcOpenClChunkStats.recordAttempt(request.chunkCount(), layout.densityOutputBytes());
        DfcOpenClRuntime.ChunkDensityPrototypeResult result =
                DfcOpenClRuntime.tryEvaluateChunkDensityPrototype(compiled, request);
        if (result.success()) {
            DfcOpenClChunkStats.recordSuccess(request.chunkCount(),
                    layout.densityOutputBytes(), result.elapsedNanos());
            return new Result(true, DfcOpenClChunkResult.densities(result.densities()), "ok");
        }
        String reason = result.reason();
        if ("opencl".equals(reason)) {
            DfcOpenClChunkStats.recordFailure(reason);
        } else {
            DfcOpenClChunkStats.recordSkip(reason);
        }
        return Result.empty(reason);
    }
}
