package dev.sixik.generator_accelerator.api.config;

public final class GAConfig {
    public int version = 12;
    public boolean enableNoisePath = true;
    public boolean enableDensityCompilerPatch = true;
    public boolean enableBiomePath = true;
    public final BiomeClimateConfig biomeClimate = new BiomeClimateConfig();
    public final DfcDebugConfig dfc = new DfcDebugConfig();

    void refreshFromSystemProperties() {
        enableNoisePath = bool("ga.config.enableNoisePatch", enableNoisePath);
        enableDensityCompilerPatch = bool("ga.config.enableDensityCompilerPatch", enableDensityCompilerPatch);
        enableBiomePath = bool("ga.config.enableBiomePatch", enableBiomePath);
        biomeClimate.refresh();
        dfc.refresh();
    }

    static boolean bool(String key, boolean fallback) {
        return Boolean.parseBoolean(System.getProperty(key, Boolean.toString(fallback)));
    }

    static int integer(String key, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static long longValue(String key, long fallback) {
        try {
            return Long.parseLong(System.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static double doubleValue(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static String string(String key, String fallback) {
        return System.getProperty(key, fallback);
    }

    public static final class BiomeClimateConfig {
        public boolean secondWarmStart = true;
        public boolean sortChildrenByDistance = true;
        public boolean adaptiveQueryCache = true;
        public int queryCacheSize = 64;
        public int queryCacheDisableProbes = 2048;
        public int queryCacheDisableHitRateShift = 7;
        public int linearSearchThreshold = 0;

        void refresh() {
            secondWarmStart = bool("ga.climate.secondWarmStart", secondWarmStart);
            sortChildrenByDistance = bool("ga.climate.sortChildrenByDistance", sortChildrenByDistance);
            adaptiveQueryCache = bool("ga.climate.adaptiveQueryCache", adaptiveQueryCache);
            queryCacheSize = integer("ga.climate.queryCacheSize", queryCacheSize);
            queryCacheDisableProbes = integer("ga.climate.queryCacheDisableProbes", queryCacheDisableProbes);
            queryCacheDisableHitRateShift = integer("ga.climate.queryCacheDisableHitRateShift", queryCacheDisableHitRateShift);
            linearSearchThreshold = integer("ga.climate.linearSearchThreshold", linearSearchThreshold);
        }
    }

    public static final class DfcDebugConfig {
        public boolean splineRuntimeStats = false;
        public int splineRuntimeStatsSampleShift = 8;
        public boolean splineRuntimeStatsEmit = true;
        public boolean splineSegmentLut = false;
        public int splineSegmentLutMinPoints = 9;
        public int splineSegmentLutBuckets = 128;
        public String splineSearchMode = "auto";
        public int splineLinearSearchMaxPoints = 3;
        public boolean cellFillStats = false;
        public boolean beardifierStats = false;
        public boolean noiseChunkTimingStats = false;
        public boolean noiseChunkStageTimingStats = true;
        public boolean cellFillResidualClassDebug = false;
        public boolean cellFillParity = false;
        public int cellFillParityMaxChecks = 1024;
        public double cellFillParityEpsilon = 1.0E-9;
        public boolean cacheFastPathStats = false;
        public int splineStatsMaxTrackedClasses = 256;
        public int cellFillStatsMaxTrackedClasses = 256;
        public int registryMaxEntries = 4096;
        public boolean logSplineSearch = false;
        public int latticeMinHoistSize = 5;
        public boolean compileMarkerInners = false;
        public boolean lazyCellCacheCompile = false;
        public int lazyCellCacheCompileMax = 128;
        public boolean fillSliceLazyCompile = false;
        public int fillSliceLazyCompileMax = 0;
        public boolean fillSliceGpuPrototype = false;
        public boolean gpuCellFillPrototype = false;
        public int randomStateCompileMax = 8;
        public String randomStateCompileRouterRoots = "all";
        public boolean randomStateCompileSampler = true;
        public int gpuRuntimeBatchMax = 0;
        public int gpuRuntimeMinPoints = 1024;
        public int gpuRuntimeMicroBatchMax = 8;
        public int gpuRuntimeMicroBatchMin = 2;
        public long gpuRuntimeMicroBatchCollectNanos = 100_000L;
        public long gpuRuntimeMicroBatchWaitNanos = 0L;
        public int gpuRuntimeMicroBatchBackoffSingleStreak = 1;
        public int gpuRuntimeMicroBatchBackoffBusyStreak = 32;
        public int gpuRuntimeMicroBatchBackoffBatches = 64;
        public int gpuRuntimeParityBatches = 8;
        public boolean gpuRuntimeOpportunisticLock = true;
        public long gpuRuntimeLockWaitNanos = 0L;
        public boolean gpuDirectGeneratedLauncher = true;
        public boolean cellFillDirectExternResidual = false;
        public boolean cellFillAddExternOverride = false;
        public boolean cellFillAddBeardifierOverride = false;
        public boolean cellFillScalarMarkerOverride = false;
        public boolean cellFillScalarMarkerLazyRangeChoiceZ = false;
        public boolean cellCacheFastFillers = true;
        public boolean cellCacheFastFillBeardifier = false;
        public boolean warmerNoiseSettings = true;
        public boolean warmerRawDensityFunctions = false;
        public int warmerMaxSettings = 8;
        public int warmerMaxDensityFunctions = -1;

        void refresh() {
            splineRuntimeStats = bool("dfc.codegen.splineRuntimeStats", splineRuntimeStats);
            splineRuntimeStatsSampleShift = integer("dfc.codegen.splineRuntimeStats.sampleShift", splineRuntimeStatsSampleShift);
            splineRuntimeStatsEmit = bool("dfc.codegen.splineRuntimeStats.emit", splineRuntimeStatsEmit);
            splineSegmentLut = bool("dfc.codegen.splineSegmentLut", splineSegmentLut);
            splineSegmentLutMinPoints = integer("dfc.codegen.splineSegmentLutMinPoints", splineSegmentLutMinPoints);
            splineSegmentLutBuckets = integer("dfc.codegen.splineSegmentLutBuckets", splineSegmentLutBuckets);
            splineSearchMode = string("dfc.codegen.splineSearchMode", splineSearchMode);
            splineLinearSearchMaxPoints = integer("dfc.codegen.splineLinearSearchMaxPoints", splineLinearSearchMaxPoints);
            cellFillStats = bool("dfc.cellfill.stats", cellFillStats);
            beardifierStats = bool("ga.beardifier.stats", beardifierStats);
            noiseChunkTimingStats = bool("ga.noiseChunk.timingStats", noiseChunkTimingStats);
            noiseChunkStageTimingStats = bool("ga.noiseChunk.stageTimingStats", noiseChunkStageTimingStats);
            cellFillResidualClassDebug = bool("dfc.cellfill.stats.residualClassDebug", cellFillResidualClassDebug);
            cellFillParity = bool("dfc.cellfill.parity", cellFillParity);
            cellFillParityMaxChecks = integer("dfc.cellfill.parity.maxChecks", cellFillParityMaxChecks);
            cellFillParityEpsilon = doubleValue("dfc.cellfill.parity.epsilon", cellFillParityEpsilon);
            cacheFastPathStats = bool("ga.dfc.cacheFastPath.stats", cacheFastPathStats);
            splineStatsMaxTrackedClasses = integer("ga.dfc.splineStats.maxTrackedClasses", splineStatsMaxTrackedClasses);
            cellFillStatsMaxTrackedClasses = integer("ga.dfc.cellFillStats.maxTrackedClasses", cellFillStatsMaxTrackedClasses);
            registryMaxEntries = integer("ga.dfc.registry.maxEntries", registryMaxEntries);
            logSplineSearch = bool("dfc.codegen.logSplineSearch", logSplineSearch);
            latticeMinHoistSize = integer("dfc.codegen.latticeMinHoistSize", latticeMinHoistSize);
            compileMarkerInners = bool("dfc.compileMarkerInners", compileMarkerInners);
            lazyCellCacheCompile = bool("ga.dfc.lazyCellCacheCompile", lazyCellCacheCompile);
            lazyCellCacheCompileMax = integer("ga.dfc.lazyCellCacheCompile.max", lazyCellCacheCompileMax);
            fillSliceLazyCompile = bool("ga.dfc.fillSliceLazyCompile", fillSliceLazyCompile);
            fillSliceLazyCompileMax = integer("ga.dfc.fillSliceLazyCompile.max", fillSliceLazyCompileMax);
            fillSliceGpuPrototype = bool("ga.dfc.gpu.fillSlicePrototype", fillSliceGpuPrototype);
            gpuCellFillPrototype = bool("ga.dfc.gpu.cellFillPrototype", gpuCellFillPrototype);
            randomStateCompileMax = integer("ga.dfc.randomStateCompile.max", randomStateCompileMax);
            randomStateCompileRouterRoots = string("ga.dfc.randomStateCompile.routerRoots", randomStateCompileRouterRoots);
            randomStateCompileSampler = bool("ga.dfc.randomStateCompile.sampler", randomStateCompileSampler);
            gpuRuntimeBatchMax = integer("ga.dfc.gpu.runtimeBatchMax", gpuRuntimeBatchMax);
            gpuRuntimeMinPoints = integer("ga.dfc.gpu.runtimeMinPoints", gpuRuntimeMinPoints);
            gpuRuntimeMicroBatchMax = integer("ga.dfc.gpu.runtimeMicroBatchMax", gpuRuntimeMicroBatchMax);
            gpuRuntimeMicroBatchMin = integer("ga.dfc.gpu.runtimeMicroBatchMin", gpuRuntimeMicroBatchMin);
            gpuRuntimeMicroBatchCollectNanos = longValue("ga.dfc.gpu.runtimeMicroBatchCollectNanos", gpuRuntimeMicroBatchCollectNanos);
            gpuRuntimeMicroBatchWaitNanos = longValue("ga.dfc.gpu.runtimeMicroBatchWaitNanos", gpuRuntimeMicroBatchWaitNanos);
            gpuRuntimeMicroBatchBackoffSingleStreak = integer("ga.dfc.gpu.runtimeMicroBatchBackoffSingleStreak", gpuRuntimeMicroBatchBackoffSingleStreak);
            gpuRuntimeMicroBatchBackoffBusyStreak = integer("ga.dfc.gpu.runtimeMicroBatchBackoffBusyStreak", gpuRuntimeMicroBatchBackoffBusyStreak);
            gpuRuntimeMicroBatchBackoffBatches = integer("ga.dfc.gpu.runtimeMicroBatchBackoffBatches", gpuRuntimeMicroBatchBackoffBatches);
            gpuRuntimeParityBatches = integer("ga.dfc.gpu.runtimeParityBatches", gpuRuntimeParityBatches);
            gpuRuntimeOpportunisticLock = bool("ga.dfc.gpu.opportunisticRuntimeLock", gpuRuntimeOpportunisticLock);
            gpuRuntimeLockWaitNanos = longValue("ga.dfc.gpu.runtimeLockWaitNanos", gpuRuntimeLockWaitNanos);
            gpuDirectGeneratedLauncher = bool("ga.dfc.gpu.directGeneratedLauncher", gpuDirectGeneratedLauncher);
            cellFillDirectExternResidual = bool("dfc.codegen.cellFillDirectExternResidual", cellFillDirectExternResidual);
            cellFillAddExternOverride = bool("dfc.codegen.cellFillAddExternOverride", cellFillAddExternOverride);
            cellFillAddBeardifierOverride = bool("dfc.codegen.cellFillAddBeardifierOverride", cellFillAddBeardifierOverride);
            cellFillScalarMarkerOverride = bool("dfc.codegen.cellFillScalarMarkerOverride", cellFillScalarMarkerOverride);
            cellFillScalarMarkerLazyRangeChoiceZ = bool("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ", cellFillScalarMarkerLazyRangeChoiceZ);
            cellCacheFastFillers = bool("ga.dfc.cellCacheFastFillers", cellCacheFastFillers);
            cellCacheFastFillBeardifier = bool("ga.dfc.cellCacheFastFillers.beardifier", cellCacheFastFillBeardifier);
            warmerNoiseSettings = bool("dfc.warmer.noiseSettings", warmerNoiseSettings);
            warmerRawDensityFunctions = bool("dfc.warmer.rawDensityFunctions", warmerRawDensityFunctions);
            warmerMaxSettings = integer("dfc.warmer.maxSettings", warmerMaxSettings);
            warmerMaxDensityFunctions = integer("dfc.warmer.maxDensityFunctions", warmerMaxDensityFunctions);
        }
    }
}
