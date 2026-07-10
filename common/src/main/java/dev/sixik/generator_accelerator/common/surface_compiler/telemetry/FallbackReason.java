package dev.sixik.generator_accelerator.common.surface_compiler.telemetry;

public enum FallbackReason {
    DISABLED,
    UNCERTIFIED,
    UNSAFE_RULE,
    QUARANTINED,
    HALO_UNAVAILABLE,
    SNAPSHOT_UNAVAILABLE,
    VALIDATION_REQUIRED,
    EXECUTION_FAILURE
}
