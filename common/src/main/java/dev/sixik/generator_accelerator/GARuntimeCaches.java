package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCompiledClassRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;

public final class GARuntimeCaches {
    private GARuntimeCaches() {
    }

    public static void resetForServerLifecycle() {
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
