package dev.sixik.generator_accelerator.common.surface_compiler.compat;

public enum AdapterSafetyClass {
    EXACT_INLINE,
    EXACT_ORDERED_INLINE,
    READ_ONLY_COMPILER_ITERATED_SCALAR,
    READ_ONLY_CERTIFIED_VECTOR,
    READ_ONLY_LEGACY_BLOCKPOS,
    HALO_READ_ONLY,
    CONTEXT_SENSITIVE,
    ORDERED_OPAQUE,
    MUTATING_OR_UNKNOWN,
    UNSAFE
}
