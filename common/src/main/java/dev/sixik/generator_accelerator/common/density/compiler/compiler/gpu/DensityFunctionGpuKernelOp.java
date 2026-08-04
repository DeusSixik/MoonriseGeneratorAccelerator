package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import java.util.Objects;

/**
 * Description of a custom GPU payload operation contributed by compat code.
 *
 * <p>These ops are source-fragment descriptors for the future generated-kernel path.
 * The current shared {@link GpuPayloadArithmeticKernel} does not execute arbitrary
 * source fragments, so {@link GpuPayloadCompiler} fails closed when a payload builder
 * emits one. The CPU evaluator is still required so parity/source-backend work has a
 * single semantic definition.</p>
 */
public final class DensityFunctionGpuKernelOp {
    private final String id;
    private final int inputCount;
    private final int parameterCount;
    private final CpuEvaluator cpuEvaluator;
    private final String openClExpression;

    public DensityFunctionGpuKernelOp(
            String id,
            int inputCount,
            int parameterCount,
            CpuEvaluator cpuEvaluator,
            String openClExpression) {
        this.id = normalizeId(id);
        if (inputCount < 0 || inputCount > 3) {
            throw new IllegalArgumentException("inputCount must be in [0, 3]: " + inputCount);
        }
        if (parameterCount < 0 || parameterCount > 4) {
            throw new IllegalArgumentException("parameterCount must be in [0, 4]: " + parameterCount);
        }
        this.inputCount = inputCount;
        this.parameterCount = parameterCount;
        this.cpuEvaluator = Objects.requireNonNull(cpuEvaluator, "cpuEvaluator");
        this.openClExpression = openClExpression == null ? "" : openClExpression.trim();
    }

    public static DensityFunctionGpuKernelOp sourceExpression(
            String id,
            int inputCount,
            int parameterCount,
            CpuEvaluator cpuEvaluator,
            String openClExpression) {
        return new DensityFunctionGpuKernelOp(id, inputCount, parameterCount, cpuEvaluator, openClExpression);
    }

    public String id() {
        return id;
    }

    public int inputCount() {
        return inputCount;
    }

    public int parameterCount() {
        return parameterCount;
    }

    public CpuEvaluator cpuEvaluator() {
        return cpuEvaluator;
    }

    public String openClExpression() {
        return openClExpression;
    }

    public boolean hasOpenClExpression() {
        return !openClExpression.isBlank();
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Custom GPU op id must not be blank");
        }
        return id.trim();
    }

    @FunctionalInterface
    public interface CpuEvaluator {
        double compute(
                double a,
                double b,
                double c,
                int blockX,
                int blockY,
                int blockZ,
                double p0,
                double p1,
                double p2,
                double p3);
    }
}
