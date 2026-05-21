package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClConfig;

public final class DfcOpenClChunkRuntime {
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
        DfcOpenClChunkStats.recordCall();
        Attempt attempt = preflight(request, OutputMode.DENSITY);
        if (!attempt.allowed()) {
            DfcOpenClChunkStats.recordSkip(attempt.reason());
            return Result.empty(attempt.reason());
        }
        DfcOpenClChunkStats.recordSkip("no_plan");
        return Result.empty("no_plan");
    }
}
