package dev.sixik.generator_accelerator.common.surface_compiler.callout;

import java.util.Objects;

public record SurfaceVectorInput(
        int[] x,
        int[] y,
        int[] z,
        int offset,
        int length,
        SurfaceCalloutScratch scratch
) {
    public SurfaceVectorInput {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
        if (offset < 0 || length < 0 || offset + length > x.length || offset + length > y.length || offset + length > z.length) {
            throw new IllegalArgumentException("invalid vector window");
        }
    }
}
