package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCompiledClassRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.beardifier.BeardifierStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.MapAllSession;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadBatchExecutor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadRuntimeRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RandomStateCompileBudget;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;

public final class GARuntimeCaches {
    private GARuntimeCaches() {
    }

    public static void resetForServerLifecycle() {
        NoiseSpecCache.clear();
        GlobalCompileCache.INSTANCE.clear();
        GpuPayloadRuntimeRegistry.clear();
        GpuPayloadBatchExecutor.resetRuntimeState();
        CompilingVisitor.global().clear();
        DfcCompiledClassRegistry.clear();
        CompiledDensityFunction.resetMapAllStats();
        MapAllSession.resetStats();
        DfcCacheFastPath.resetStats();
        DfcCellFillStats.reset();
        DfcSplineStats.reset();
        BeardifierStats.reset();
        RouterPipeline.resetStats();
        RandomStateCompileBudget.reset();
        RegistryWarmer.clear();
    }
}
