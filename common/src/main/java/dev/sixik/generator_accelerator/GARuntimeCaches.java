package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.common.carver.CarverReplaceableCache;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCompiledClassRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.features.FeatureCacheEpoch;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineCompatibility;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceProgramCache;

public final class GARuntimeCaches {
    private GARuntimeCaches() {
    }

    public static void resetForServerLifecycle() {
        FeatureCacheEpoch.bump();
        DecorationPipelineCompatibility.clearSessionCaches();
        SurfaceProgramCache.clear();
        CarverReplaceableCache.clear();
        NoiseSpecCache.clear();
        GlobalCompileCache.INSTANCE.clear();
        DfcCompiledClassRegistry.clear();
        RegistryWarmer.clear();
    }
}
