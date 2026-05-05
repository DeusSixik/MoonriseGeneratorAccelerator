package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import java.lang.invoke.MethodHandles;

/**
 * Sibling of {@link CompiledDensityFunction} sharing its package — exposes the host's
 * full-power {@link MethodHandles.Lookup} to {@link HiddenClassLoader} without making it
 * world-accessible. Defining hidden classes in this same package is required so the
 * generated nestmate can access {@link CompiledDensityFunction}'s {@code protected}
 * instance fields directly via {@code GETFIELD}.
 */
final class HostLookupAccess {
    private HostLookupAccess() {}

    static MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }
}
