package dev.sixik.generator_accelerator.common.surface_compiler.validate;

import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceCompilerCaches;
import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceMetrics;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;

public final class QuarantineManager {
    public boolean isQuarantined(FingerprintCacheKey key) {
        return SurfaceCompilerCaches.store().isQuarantined(key);
    }

    public void quarantine(FingerprintCacheKey key, FallbackReason reason) {
        SurfaceCompilerCaches.quarantine(key);
        SurfaceMetrics.quarantine(reason);
    }
}
