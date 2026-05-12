package dev.sixik.generator_accelerator.common.worldgen.optimizer;

public enum WorldgenDeoptReason {
    NONE,
    UNRECOGNIZED_PATTERN,
    UNSAFE_EFFECT,
    GUARD_MISMATCH,
    PARITY_MISMATCH,
    OPTIMIZED_EXCEPTION,
    COLLISION_POLICY_MISMATCH,
    CROSS_CHUNK_BUDGET_EXCEEDED,
    RESOURCE_BUDGET_EXCEEDED,
    SLOWER_THAN_VANILLA
}
