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
}
