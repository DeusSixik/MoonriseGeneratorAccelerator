package dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;

/**
 * Stable runtime ABI for generated surface kernels.
 *
 * <p>Generated code is intentionally constrained to this package-owned context
 * rather than depending directly on Mojang internals. Tier 0 direct writes are
 * only selected after synthetic coverage and direct-write certification.</p>
 */
public interface GeneratedKernel {
    boolean execute(SurfaceExecutionContext context);
}
