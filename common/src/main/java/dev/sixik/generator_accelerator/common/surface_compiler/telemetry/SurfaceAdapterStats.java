package dev.sixik.generator_accelerator.common.surface_compiler.telemetry;

import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterSafetyClass;

public record SurfaceAdapterStats(
        String adapterId,
        AdapterSafetyClass safetyClass,
        long calls,
        long failures,
        boolean vectorEligible,
        long vectorCalls,
        long vectorFailures
) {
}
