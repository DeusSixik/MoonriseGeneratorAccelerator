package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.common.carver.CarverReplaceableCache;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCompiledClassRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.features.FeatureCacheEpoch;
import dev.sixik.generator_accelerator.common.features.cache.SharedWeakCache;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineCompatibility;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceRuntime;

public final class GARuntimeCaches {
    private GARuntimeCaches() {
    }

    public static void resetForServerLifecycle() {
        FeatureCacheEpoch.bump();
        DecorationPipelineCompatibility.clearSessionCaches();
        SharedWeakCache.clearAll();
        SurfaceRuntime.clearCaches();
        GAChunkWorkspaceRuntime.resetForServerLifecycle();
        CarverReplaceableCache.clear();
        NoiseSpecCache.clear();
        GlobalCompileCache.INSTANCE.clear();
        CompilingVisitor.global().clear();
        DfcCompiledClassRegistry.clear();
        DfcCellFillStats.reset();
        DfcNativePlanningStats.reset();
        DfcSplineStats.reset();
        RegistryWarmer.clear();
    }
}