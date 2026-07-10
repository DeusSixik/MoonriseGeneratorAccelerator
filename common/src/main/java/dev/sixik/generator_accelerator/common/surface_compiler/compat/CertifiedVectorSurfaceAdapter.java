package dev.sixik.generator_accelerator.common.surface_compiler.compat;

import dev.sixik.generator_accelerator.common.surface_compiler.callout.SurfaceVectorInput;
import dev.sixik.generator_accelerator.common.surface_compiler.callout.SurfaceVectorOutput;

/**
 * Explicitly certified vector adapter ABI for read-only surface predicates.
 *
 * <p>This interface is intentionally primitive-array based. It keeps BlockPos
 * and mutable vanilla context objects out of adapter ownership, so the compiler
 * can batch calls without exposing reusable objects that may escape.</p>
 */
public interface CertifiedVectorSurfaceAdapter extends SurfaceAdapter {
    int vectorWidth();

    default boolean canEvaluateVector(SurfaceVectorInput input) {
        return input != null && input.length() > 0 && input.length() <= vectorWidth();
    }

    void evaluateVector(SurfaceVectorInput input, SurfaceVectorOutput output);
}
