package dev.sixik.generator_accelerator.common.features.pipeline;

final class SelectorPlan {
    static final int MODE_RANDOM_FEATURE = 0;
    static final int MODE_RANDOM_BOOLEAN = 1;
    static final int MODE_SIMPLE_RANDOM = 2;

    private final int mode;
    private final DecorationKernelPlan[] branchKernels;
    private final float[] branchChances;

    SelectorPlan(
            int mode,
            DecorationKernelPlan[] branchKernels,
            float[] branchChances
    ) {
        this.mode = mode;
        this.branchKernels = branchKernels;
        this.branchChances = branchChances;
    }

    int mode() {
        return this.mode;
    }

    DecorationKernelPlan[] branchKernels() {
        return this.branchKernels;
    }

    float[] branchChances() {
        return this.branchChances;
    }
}
