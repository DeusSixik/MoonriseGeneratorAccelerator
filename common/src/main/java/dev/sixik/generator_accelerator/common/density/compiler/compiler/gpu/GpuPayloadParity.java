package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Diagnostic parity probe for the GPU-shaped primitive payload. */
public final class GpuPayloadParity {
    private static final double EPSILON = 1.0E-12D;
    private static final int[] SAMPLE_X = {-16, 0, 16};
    private static final int[] SAMPLE_Y = {-64, 0, 64, 128};
    private static final int[] SAMPLE_Z = {-16, 0, 16};

    private GpuPayloadParity() {
    }

    public static Report check(CompiledDensityFunction compiled, GpuPayloadCompiler.Result gpuPayload) {
        if (compiled == null || gpuPayload == null || !gpuPayload.supported() || gpuPayload.payload() == null) {
            return Report.skipped();
        }

        GpuIrPayload payload = gpuPayload.payload();
        if (payload.hasExternInputs()) {
            return Report.skipped();
        }
        int points = 0;
        double maxAbsError = 0.0D;
        String firstMismatch = "none";

        for (int x : SAMPLE_X) {
            for (int y : SAMPLE_Y) {
                for (int z : SAMPLE_Z) {
                    points++;
                    double cpu;
                    double mirror;
                    try {
                        PointContext context = new PointContext(x, y, z);
                        cpu = compiled.compute(context);
                        mirror = GpuPayloadCpuEvaluator.compute(payload, x, y, z);
                    } catch (Throwable t) {
                        return new Report(true, false, points, Double.POSITIVE_INFINITY,
                                "exception at x=" + x + ", y=" + y + ", z=" + z + ": " + t);
                    }

                    double absError = absError(cpu, mirror);
                    if (absError > maxAbsError) {
                        maxAbsError = absError;
                    }
                    if (!sameValue(cpu, mirror) && "none".equals(firstMismatch)) {
                        firstMismatch = "x=" + x + ", y=" + y + ", z=" + z
                                + ", cpu=" + cpu + ", mirror=" + mirror + ", absError=" + absError;
                    }
                }
            }
        }

        return new Report(true, "none".equals(firstMismatch), points, maxAbsError, firstMismatch);
    }

    private static boolean sameValue(double cpu, double mirror) {
        if (Double.doubleToRawLongBits(cpu) == Double.doubleToRawLongBits(mirror)) {
            return true;
        }
        if (Double.isNaN(cpu) && Double.isNaN(mirror)) {
            return true;
        }
        return absError(cpu, mirror) <= EPSILON;
    }

    private static double absError(double cpu, double mirror) {
        double error = Math.abs(cpu - mirror);
        return Double.isNaN(error) ? Double.POSITIVE_INFINITY : error;
    }

    private record PointContext(int blockX, int blockY, int blockZ) implements DensityFunction.FunctionContext {
    }

    public record Report(boolean checked, boolean passed, int pointsChecked, double maxAbsError, String firstMismatch) {
        public static Report skipped() {
            return new Report(false, false, 0, 0.0D, "none");
        }
    }
}
