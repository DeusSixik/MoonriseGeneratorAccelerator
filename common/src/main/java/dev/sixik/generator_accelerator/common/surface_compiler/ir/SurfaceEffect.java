package dev.sixik.generator_accelerator.common.surface_compiler.ir;

public enum SurfaceEffect {
    PURE,
    READ_ONLY_STABLE,
    READ_ONLY_ORDERED,
    STATEFUL_RNG,
    STATEFUL_NOISE,
    OPAQUE_CALLOUT,
    MUTATING,
    UNSAFE;

    public boolean mayReorder() {
        return this == PURE || this == READ_ONLY_STABLE;
    }
}
