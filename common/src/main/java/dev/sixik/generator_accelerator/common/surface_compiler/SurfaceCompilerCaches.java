package dev.sixik.generator_accelerator.common.surface_compiler;

import dev.sixik.generator_accelerator.common.surface_compiler.cache.BoundedProgramStore;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.EpochClassLoader;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;

public final class SurfaceCompilerCaches {
    private static final BoundedProgramStore STORE = new BoundedProgramStore(SurfaceCompilerConfig.CACHE_MAX_SIZE);

    private SurfaceCompilerCaches() {
    }

    public static BoundedProgramStore store() {
        return STORE;
    }

    public static void clear() {
        STORE.clear();
        EpochClassLoader.retireAll();
    }

    public static void quarantine(FingerprintCacheKey key) {
        STORE.quarantine(key);
    }
}
