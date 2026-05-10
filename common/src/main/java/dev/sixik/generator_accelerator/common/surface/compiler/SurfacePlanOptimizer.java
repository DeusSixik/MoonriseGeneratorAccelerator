package dev.sixik.generator_accelerator.common.surface.compiler;

public final class SurfacePlanOptimizer {
    private SurfacePlanOptimizer() {
    }

    public static SurfaceProgram optimize(SurfaceProgram program) {
        // The compiler already flattens sequences and lowers constant blocks.
        return program;
    }
}
