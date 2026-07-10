package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

public enum SurfaceTier {
    QUARANTINED,
    CERTIFIED_DIRECT_JIT,
    GUARDED_HYBRID_JIT,
    MASK_INTERPRETER,
    VANILLA_CLEAN_PATH,
    VALIDATION
}
