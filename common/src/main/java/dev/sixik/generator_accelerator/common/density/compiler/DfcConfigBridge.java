package dev.sixik.generator_accelerator.common.density.compiler;

import dev.sixik.generator_accelerator.api.config.GAConfig;
import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import dev.sixik.generator_accelerator.common.beardifier.BeardifierStats;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkTimingStats;

public final class DfcConfigBridge {

    private static volatile boolean applied;

    private DfcConfigBridge() {
    }

    public static void applySystemPropertiesFromConfig() {
        if (applied) {
            return;
        }
        synchronized (DfcConfigBridge.class) {
            if (applied) {
                return;
            }

            GAConfig configRoot = GAConfigHolder.getConfig();
            GAConfig.DfcDebugConfig dfc = configRoot.dfc;
            GAConfig.BiomeClimateConfig biome = configRoot.biomeClimate;

            setBoolean("dfc.codegen.splineRuntimeStats", dfc.splineRuntimeStats);
            setInt("dfc.codegen.splineRuntimeStats.sampleShift", dfc.splineRuntimeStatsSampleShift);
            setBoolean("dfc.codegen.splineRuntimeStats.emit", dfc.splineRuntimeStatsEmit);
            setBoolean("dfc.codegen.splineSegmentLut", dfc.splineSegmentLut);
            setInt("dfc.codegen.splineSegmentLutMinPoints", dfc.splineSegmentLutMinPoints);
            setInt("dfc.codegen.splineSegmentLutBuckets", dfc.splineSegmentLutBuckets);
            setString("dfc.codegen.splineSearchMode", dfc.splineSearchMode);
            setInt("dfc.codegen.splineLinearSearchMaxPoints", dfc.splineLinearSearchMaxPoints);
            setBoolean("dfc.cellfill.stats", dfc.cellFillStats);
            setBoolean("ga.beardifier.stats", dfc.beardifierStats);
            BeardifierStats.setEnabled(dfc.beardifierStats);
            setBoolean("ga.noiseChunk.timingStats", dfc.noiseChunkTimingStats);
            NoiseChunkTimingStats.setEnabled(dfc.noiseChunkTimingStats);
            setBoolean("dfc.cellfill.stats.residualClassDebug", dfc.cellFillResidualClassDebug);
            setBoolean("dfc.cellfill.parity", dfc.cellFillParity);
            setInt("dfc.cellfill.parity.maxChecks", dfc.cellFillParityMaxChecks);
            setDouble("dfc.cellfill.parity.epsilon", dfc.cellFillParityEpsilon);
            setBoolean("ga.dfc.cacheFastPath.stats", dfc.cacheFastPathStats);
            setInt("ga.dfc.splineStats.maxTrackedClasses", dfc.splineStatsMaxTrackedClasses);
            setInt("ga.dfc.cellFillStats.maxTrackedClasses", dfc.cellFillStatsMaxTrackedClasses);
            setInt("ga.dfc.registry.maxEntries", dfc.registryMaxEntries);
            setBoolean("dfc.codegen.logSplineSearch", dfc.logSplineSearch);
            setInt("dfc.codegen.latticeMinHoistSize", dfc.latticeMinHoistSize);
            setBoolean("dfc.compileMarkerInners", dfc.compileMarkerInners);
            setBoolean("ga.dfc.lazyCellCacheCompile", dfc.lazyCellCacheCompile);
            setInt("ga.dfc.lazyCellCacheCompile.max", Math.max(0, dfc.lazyCellCacheCompileMax));
            setBoolean("ga.dfc.fillSliceLazyCompile", dfc.fillSliceLazyCompile);
            setInt("ga.dfc.fillSliceLazyCompile.max", Math.max(0, dfc.fillSliceLazyCompileMax));
            setBoolean("ga.dfc.gpu.fillSlicePrototype", dfc.fillSliceGpuPrototype);
            setBoolean("ga.dfc.gpu.cellFillPrototype", dfc.gpuCellFillPrototype);
            setInt("ga.dfc.randomStateCompile.max", dfc.randomStateCompileMax);
            setString("ga.dfc.randomStateCompile.routerRoots", dfc.randomStateCompileRouterRoots);
            setBoolean("ga.dfc.randomStateCompile.sampler", dfc.randomStateCompileSampler);
            setInt("ga.dfc.gpu.runtimeBatchMax", dfc.gpuRuntimeBatchMax);
            setInt("ga.dfc.gpu.runtimeMinPoints", dfc.gpuRuntimeMinPoints);
            setInt("ga.dfc.gpu.runtimeMicroBatchMax", dfc.gpuRuntimeMicroBatchMax);
            setInt("ga.dfc.gpu.runtimeMicroBatchMin", dfc.gpuRuntimeMicroBatchMin);
            setLong("ga.dfc.gpu.runtimeMicroBatchCollectNanos", dfc.gpuRuntimeMicroBatchCollectNanos);
            setLong("ga.dfc.gpu.runtimeMicroBatchWaitNanos", dfc.gpuRuntimeMicroBatchWaitNanos);
            setInt("ga.dfc.gpu.runtimeMicroBatchBackoffSingleStreak", dfc.gpuRuntimeMicroBatchBackoffSingleStreak);
            setInt("ga.dfc.gpu.runtimeMicroBatchBackoffBusyStreak", dfc.gpuRuntimeMicroBatchBackoffBusyStreak);
            setInt("ga.dfc.gpu.runtimeMicroBatchBackoffBatches", dfc.gpuRuntimeMicroBatchBackoffBatches);
            setInt("ga.dfc.gpu.runtimeParityBatches", Math.max(0, dfc.gpuRuntimeParityBatches));
            setBoolean("ga.dfc.gpu.opportunisticRuntimeLock", dfc.gpuRuntimeOpportunisticLock);
            setLong("ga.dfc.gpu.runtimeLockWaitNanos", dfc.gpuRuntimeLockWaitNanos);
            setBoolean("ga.dfc.gpu.directGeneratedLauncher", dfc.gpuDirectGeneratedLauncher);
            setBoolean("dfc.codegen.cellFillDirectExternResidual", dfc.cellFillDirectExternResidual);
            setBoolean("dfc.codegen.cellFillAddExternOverride", dfc.cellFillAddExternOverride);
            setBoolean("dfc.codegen.cellFillAddBeardifierOverride", dfc.cellFillAddBeardifierOverride);
            setBoolean("dfc.codegen.cellFillScalarMarkerOverride", dfc.cellFillScalarMarkerOverride);
            setBoolean("dfc.warmer.noiseSettings", dfc.warmerNoiseSettings);
            setBoolean("dfc.warmer.rawDensityFunctions", dfc.warmerRawDensityFunctions);
            setBudget("dfc.warmer.maxSettings", dfc.warmerMaxSettings);
            setBudget("dfc.warmer.maxDensityFunctions", dfc.warmerMaxDensityFunctions);

            setBoolean("ga.climate.secondWarmStart", biome.secondWarmStart);
            setBoolean("ga.climate.sortChildrenByDistance", biome.sortChildrenByDistance);
            setBoolean("ga.climate.adaptiveQueryCache", biome.adaptiveQueryCache);
            setInt("ga.climate.queryCacheSize", biome.queryCacheSize);
            setInt("ga.climate.queryCacheDisableProbes", biome.queryCacheDisableProbes);
            setInt("ga.climate.queryCacheDisableHitRateShift", biome.queryCacheDisableHitRateShift);
            setInt("ga.climate.linearSearchThreshold", biome.linearSearchThreshold);

            applied = true;
        }
    }

    private static void setBoolean(String key, boolean value) {
        System.setProperty(key, Boolean.toString(value));
    }

    private static void setInt(String key, int value) {
        System.setProperty(key, Integer.toString(value));
    }

    private static void setLong(String key, long value) {
        System.setProperty(key, Long.toString(value));
    }

    private static void setDouble(String key, double value) {
        System.setProperty(key, Double.toString(value));
    }

    private static void setString(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        System.setProperty(key, value.trim());
    }

    private static void setBudget(String key, int value) {
        if (value < 0) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, Integer.toString(value));
    }
}
