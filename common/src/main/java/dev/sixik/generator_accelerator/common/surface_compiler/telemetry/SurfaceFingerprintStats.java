package dev.sixik.generator_accelerator.common.surface_compiler.telemetry;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;

public record SurfaceFingerprintStats(String fingerprint, SurfaceTier tier, FallbackReason fallbackReason, long prepares, long executions) {
}
