package dev.sixik.generator_accelerator.diagnostics;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.aquifer.region.GARegionalAquiferAtlas;
import dev.sixik.generator_accelerator.common.beardifier.region.GARegionalBeardifierAtlas;
import dev.sixik.generator_accelerator.common.biome.region.GARegionalBiomeSectionRaster;
import dev.sixik.generator_accelerator.common.biome.region.GARegionalClimateQuartRaster;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineMetrics;
import dev.sixik.generator_accelerator.common.features.vm.FeatureVmMetrics;
import dev.sixik.generator_accelerator.common.noise.GANoiseFillMetrics;
import dev.sixik.generator_accelerator.common.noise.region.GARegionalNoiseBrickCache;
import dev.sixik.generator_accelerator.common.noise.region.GARegionalDensitySliceCache;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceMetrics;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.GAWorldgenPipelineStatus;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitMetrics;
import dev.sixik.generator_accelerator.common.worldgen.diagnostics.GAWorldgenDiagnosticsFeedback;
import dev.sixik.generator_accelerator.common.worldgen.lifecycle.GAOuterLifecycleMetrics;
import dev.sixik.generator_accelerator.common.worldgen.optimizer.WorldgenOptimizerMetrics;
import dev.sixik.generator_accelerator.common.worldgen.parallel.GAChunkStatusPipeline;
import dev.sixik.generator_accelerator.common.worldgen.parallel.GACustomChunkGraphScheduler;
import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenProfileMetrics;
import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenRegistryScanOrchestrator;
import dev.sixik.generator_accelerator.common.worldgen.region.GARegionalPrewarmManager;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceMetrics;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspacePool;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Low-overhead diagnostics bundle writer.
 *
 * <p>Regular counters catch GA-owned hot paths. JFR must be enabled for real
 * allocation-by-class and allocation-by-stack data.
 */
public final class GADiagnostics {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final Object JFR_LOCK = new Object();

    private static volatile boolean commandEnabled;
    private static volatile GcTotals gcBaseline = captureGcTotals();
    private static volatile Recording activeRecording;
    private static volatile Path activeJfrPath;

    private GADiagnostics() {
    }

    public static void onModInit() {
        syncMetricFlagsFromProperties();
        resetBaseline();
        if (isEnabled()) {
            installShutdownHook();
        }
        startJfrIfEnabled("ga-runtime");
    }

    public static boolean isEnabled() {
        return commandEnabled
                || Boolean.getBoolean("ga.diagnostics.enabled")
                || Boolean.getBoolean("ga.benchmark.diagnostics")
                || Boolean.getBoolean("ga.diagnostics.jfr");
    }

    public static boolean isRecordingActive() {
        Recording recording = activeRecording;
        return recording != null && recording.getState() == RecordingState.RUNNING;
    }

    public static Path dumpDirectory() {
        return dumpDir();
    }

    public static Path activeJfrPath() {
        return activeJfrPath;
    }

    public static void resetBaseline() {
        gcBaseline = captureGcTotals();
    }

    public static void startJfrIfEnabled(String name) {
        if (!Boolean.getBoolean("ga.diagnostics.jfr")) {
            return;
        }
        startRecording(name, Boolean.getBoolean("ga.diagnostics.jfr.allocations"));
    }

    public static Path startRecording(String name, boolean allocationStacks) {
        synchronized (JFR_LOCK) {
            if (activeRecording != null) {
                return activeJfrPath;
            }
            try {
                Files.createDirectories(dumpDir());
                Path path = dumpDir().resolve(filePrefix(name) + ".jfr");
                Recording recording = createRecording();
                recording.setName(name);
                recording.setToDisk(true);
                recording.setMaxSize(Long.getLong("ga.diagnostics.jfr.maxSizeBytes", 1024L * 1024L * 1024L));
                int maxAgeSeconds = Integer.getInteger("ga.diagnostics.jfr.maxAgeSeconds", 3600);
                if (maxAgeSeconds > 0) {
                    recording.setMaxAge(Duration.ofSeconds(maxAgeSeconds));
                }
                configureRecording(recording, allocationStacks);
                recording.start();
                activeRecording = recording;
                activeJfrPath = path;
                GeneratorAccelerator.LOGGER.info("GA diagnostics JFR started: {}", path.toAbsolutePath());
                return path;
            } catch (Throwable throwable) {
                GeneratorAccelerator.LOGGER.warn("GA diagnostics failed to start JFR", throwable);
                return null;
            }
        }
    }

    public static Path writeDump(String reason) {
        return writeDump(reason, null);
    }

    public static Path writeDump(String reason, Map<String, ?> extra) {
        DumpResult result = writeBundle(reason, extra, true, false);
        return result.jsonPath() != null ? result.jsonPath() : result.jfrPath();
    }

    public static DumpResult writeSnapshotBundle(String reason, Map<String, ?> extra) {
        return writeBundle(reason, extra, false, true);
    }

    public static DumpResult writeStopBundle(String reason, Map<String, ?> extra) {
        return writeBundle(reason, extra, true, true);
    }

    public static Path writeBenchmarkDump(String reason, Map<String, ?> benchmark) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (benchmark != null) {
            extra.put("benchmark", new LinkedHashMap<>(benchmark));
        }
        return writeDump(reason, extra);
    }

    public static void resetAllMetrics() {
        resetBaseline();
        DecorationPipelineMetrics.reset();
        FeatureVmMetrics.reset();
        GANoiseFillMetrics.reset();
        SurfaceMetrics.reset();
        DfcCellFillStats.reset();
        DfcNativePlanningStats.reset();
        DfcSplineStats.reset();
        GAScheduler.resetMetrics();
        GAChunkStatusPipeline.resetMetrics();
        GACustomChunkGraphScheduler.resetMetrics();
        WorldgenProfileMetrics.reset();
        WorldgenRegistryScanOrchestrator.GLOBAL.reset();
        GACommitMetrics.resetGlobal();
        GAChunkWorkspaceMetrics.resetGlobal();
        GAChunkWorkspacePool.resetMetrics();
        WorldgenOptimizerMetrics.reset();
        GAOuterLifecycleMetrics.resetGlobal();
    }

    public static void enableFromCommand() {
        commandEnabled = true;
        setProperty("ga.diagnostics.enabled", "true");
        setProperty("ga.diagnostics.jfr", "true");
        setProperty("ga.diagnostics.jfr.allocations", "true");
        setProperty("ga.diagnostics.jfr.allocationSamples", "true");
        setProperty("ga.decorationPipeline.metrics", "true");
        setProperty("ga.featureVm.metrics", "true");
        setProperty("ga.surface.metrics", "true");
        setProperty("ga.noiseFill.metrics", "true");
        setProperty("ga.worldgenProfile.metrics", "true");
        setProperty("dfc.cellfill.stats", "true");
        setProperty("dfc.cellfill.stats.residualClassDebug", "true");
        setProperty("dfc.codegen.splineRuntimeStats", "true");
        syncMetricFlagsFromProperties();
        installShutdownHook();
    }

    public static void disableFromCommand() {
        commandEnabled = false;
        setProperty("ga.diagnostics.enabled", "false");
        setProperty("ga.diagnostics.jfr", "false");
        setProperty("ga.diagnostics.jfr.allocations", "false");
        setProperty("ga.diagnostics.jfr.allocationSamples", "false");
        setProperty("ga.decorationPipeline.metrics", "false");
        setProperty("ga.featureVm.metrics", "false");
        setProperty("ga.surface.metrics", "false");
        setProperty("ga.noiseFill.metrics", "false");
        setProperty("ga.worldgenProfile.metrics", "false");
        setProperty("dfc.cellfill.stats", "false");
        setProperty("dfc.cellfill.stats.residualClassDebug", "false");
        setProperty("dfc.codegen.splineRuntimeStats", "false");
        syncMetricFlagsFromProperties();
    }

    public static Path restartRecording(String name, boolean allocationStacks) {
        discardRecording();
        return startRecording(name, allocationStacks);
    }

    public static void discardRecording() {
        synchronized (JFR_LOCK) {
            Recording recording = activeRecording;
            activeRecording = null;
            activeJfrPath = null;
            if (recording != null) {
                try {
                    recording.close();
                } catch (Throwable throwable) {
                    GeneratorAccelerator.LOGGER.warn("GA diagnostics failed to close JFR recording", throwable);
                }
            }
        }
    }

    public static String statusLine() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        GcTotals totals = captureGcTotals();
        GcTotals baseline = gcBaseline;
        return "GA diagnostics: enabled=" + isEnabled()
                + ", metrics=" + metricsEnabledSummary()
                + ", jfrActive=" + isRecordingActive()
                + ", heapUsedMb=" + heap.getUsed() / (1024L * 1024L)
                + ", heapCommittedMb=" + heap.getCommitted() / (1024L * 1024L)
                + ", gcDeltaCount=" + (totals.collectionCount() - baseline.collectionCount())
                + ", gcDeltaMs=" + (totals.collectionTimeMs() - baseline.collectionTimeMs())
                + ", dumpDir=" + dumpDir().toAbsolutePath();
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> writeDump("shutdown"),
                "ga-diagnostics-shutdown"
        ));
    }

    private static void syncMetricFlagsFromProperties() {
        DecorationPipelineMetrics.setEnabled(Boolean.getBoolean("ga.decorationPipeline.metrics"));
        FeatureVmMetrics.setEnabled(Boolean.getBoolean("ga.featureVm.metrics"));
        SurfaceMetrics.setEnabled(Boolean.getBoolean("ga.surface.metrics"));
        GANoiseFillMetrics.setEnabled(Boolean.getBoolean("ga.noiseFill.metrics"));
        WorldgenProfileMetrics.setEnabled(Boolean.getBoolean("ga.worldgenProfile.metrics"));
        DfcCellFillStats.setEnabled(
                Boolean.getBoolean("dfc.cellfill.stats"),
                Boolean.getBoolean("dfc.cellfill.stats.residualClassDebug"));
        DfcSplineStats.setEnabled(Boolean.getBoolean("dfc.codegen.splineRuntimeStats"));
    }

    private static void setProperty(String name, String value) {
        System.setProperty(name, value);
    }

    private static String metricsEnabledSummary() {
        return "decoration=" + DecorationPipelineMetrics.ENABLED
                + ", featureVm=" + FeatureVmMetrics.ENABLED
                + ", surface=" + SurfaceMetrics.ENABLED
                + ", noiseFill=" + GANoiseFillMetrics.ENABLED
                + ", cellFill=" + DfcCellFillStats.ENABLED
                + ", spline=" + DfcSplineStats.ENABLED
                + ", worldgenProfiles=" + WorldgenProfileMetrics.ENABLED
                + ", scheduler=true"
                + ", workspaceLive=false"
                + ", optimizer=true"
                + ", outerLifecycle=true";
    }

    private static DumpResult writeBundle(String reason, Map<String, ?> extra, boolean stopRecording, boolean zip) {
        Path jfrPath = stopRecording ? stopJfrAndDump(reason) : dumpActiveJfrSnapshot(reason);
        Path jsonPath = null;
        Path zipPath = null;
        if (isEnabled()) {
            try {
                Files.createDirectories(dumpDir());
                Map<String, Object> root = snapshot(reason);
                if (extra != null && !extra.isEmpty()) {
                    root.put("extra", new LinkedHashMap<>(extra));
                }
                Map<String, Object> artifacts = new LinkedHashMap<>();
                if (jfrPath != null) {
                    artifacts.put(stopRecording ? "jfr" : "jfrSnapshot", jfrPath.toAbsolutePath().toString());
                }
                root.put("artifacts", artifacts);

                jsonPath = dumpDir().resolve(filePrefix(reason) + ".json");
                Files.writeString(jsonPath, DiagnosticsJson.toJson(root));
                GeneratorAccelerator.LOGGER.info("GA diagnostics dump written: {}", jsonPath.toAbsolutePath());
                if (zip) {
                    zipPath = writeZip(reason, jsonPath, jfrPath);
                }
            } catch (Throwable throwable) {
                GeneratorAccelerator.LOGGER.warn("GA diagnostics dump failed", throwable);
            }
        }
        return new DumpResult(jsonPath, jfrPath, zipPath, stopRecording);
    }

    private static Map<String, Object> snapshot(String reason) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "generator-accelerator-diagnostics-v1");
        root.put("reason", reason);
        root.put("createdAt", Instant.now().toString());
        root.put("createdAtEpochMs", System.currentTimeMillis());
        root.put("note", "Allocation-by-class/stack requires ga.diagnostics.jfr=true; in-code alloc counters are estimates.");
        root.put("runtime", runtimeSnapshot());
        root.put("process", processSnapshot());
        root.put("memory", memorySnapshot());
        root.put("gc", gcSnapshot());
        root.put("threads", threadSnapshot());
        root.put("classes", classLoadingSnapshot());
        root.put("systemProperties", filteredProperties());
        root.put("generatorAccelerator", gaSnapshot());
        return root;
    }

    private static Map<String, Object> runtimeSnapshot() {
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", runtimeBean.getName());
        out.put("vmName", runtimeBean.getVmName());
        out.put("vmVendor", runtimeBean.getVmVendor());
        out.put("vmVersion", runtimeBean.getVmVersion());
        out.put("specVersion", runtimeBean.getSpecVersion());
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("javaVendor", System.getProperty("java.vendor"));
        out.put("uptimeMs", runtimeBean.getUptime());
        out.put("startTimeMs", runtimeBean.getStartTime());
        out.put("inputArguments", runtimeBean.getInputArguments());
        out.put("availableProcessors", runtime.availableProcessors());
        out.put("heapMaxBytesRuntime", runtime.maxMemory());
        out.put("heapTotalBytesRuntime", runtime.totalMemory());
        out.put("heapFreeBytesRuntime", runtime.freeMemory());
        return out;
    }

    private static Map<String, Object> processSnapshot() {
        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("osName", osBean.getName());
        out.put("osArch", osBean.getArch());
        out.put("osVersion", osBean.getVersion());
        out.put("systemLoadAverage", osBean.getSystemLoadAverage());
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            out.put("committedVirtualMemoryBytes", sunOsBean.getCommittedVirtualMemorySize());
            out.put("freePhysicalMemoryBytes", sunOsBean.getFreeMemorySize());
            out.put("totalPhysicalMemoryBytes", sunOsBean.getTotalMemorySize());
            out.put("freeSwapBytes", sunOsBean.getFreeSwapSpaceSize());
            out.put("totalSwapBytes", sunOsBean.getTotalSwapSpaceSize());
            out.put("processCpuLoad", sunOsBean.getProcessCpuLoad());
            out.put("systemCpuLoad", sunOsBean.getCpuLoad());
            out.put("processCpuTimeNanos", sunOsBean.getProcessCpuTime());
        }
        return out;
    }

    private static Map<String, Object> memorySnapshot() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heap", memoryUsage(memoryBean.getHeapMemoryUsage()));
        out.put("nonHeap", memoryUsage(memoryBean.getNonHeapMemoryUsage()));
        out.put("objectPendingFinalizationCount", memoryBean.getObjectPendingFinalizationCount());

        List<Map<String, Object>> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", pool.getName());
            item.put("type", pool.getType().name());
            item.put("usage", memoryUsage(pool.getUsage()));
            item.put("peakUsage", memoryUsage(pool.getPeakUsage()));
            item.put("collectionUsage", memoryUsage(pool.getCollectionUsage()));
            pools.add(item);
        }
        out.put("pools", pools);
        return out;
    }

    private static Map<String, Object> gcSnapshot() {
        GcTotals totals = captureGcTotals();
        GcTotals baseline = gcBaseline;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCollectionCount", totals.collectionCount());
        out.put("totalCollectionTimeMs", totals.collectionTimeMs());
        out.put("deltaCollectionCount", totals.collectionCount() - baseline.collectionCount());
        out.put("deltaCollectionTimeMs", totals.collectionTimeMs() - baseline.collectionTimeMs());

        List<Map<String, Object>> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", collector.getName());
            item.put("collectionCount", collector.getCollectionCount());
            item.put("collectionTimeMs", collector.getCollectionTime());
            item.put("memoryPoolNames", Arrays.asList(collector.getMemoryPoolNames()));
            collectors.add(item);
        }
        out.put("collectors", collectors);
        return out;
    }

    private static Map<String, Object> threadSnapshot() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadCount", threadBean.getThreadCount());
        out.put("daemonThreadCount", threadBean.getDaemonThreadCount());
        out.put("peakThreadCount", threadBean.getPeakThreadCount());
        out.put("totalStartedThreadCount", threadBean.getTotalStartedThreadCount());
        out.put("currentThreadCpuTimeSupported", threadBean.isCurrentThreadCpuTimeSupported());
        out.put("threadContentionMonitoringSupported", threadBean.isThreadContentionMonitoringSupported());
        long[] deadlocked = threadBean.findDeadlockedThreads();
        long[] monitorDeadlocked = threadBean.findMonitorDeadlockedThreads();
        out.put("deadlockedThreadIds", deadlocked == null ? List.of() : Arrays.stream(deadlocked).boxed().toList());
        out.put("monitorDeadlockedThreadIds", monitorDeadlocked == null ? List.of() : Arrays.stream(monitorDeadlocked).boxed().toList());
        return out;
    }

    private static Map<String, Object> classLoadingSnapshot() {
        ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loadedClassCount", classBean.getLoadedClassCount());
        out.put("totalLoadedClassCount", classBean.getTotalLoadedClassCount());
        out.put("unloadedClassCount", classBean.getUnloadedClassCount());
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean != null) {
            out.put("compilerName", compilationBean.getName());
            out.put("totalCompilationTimeMonitoringSupported", compilationBean.isCompilationTimeMonitoringSupported());
            if (compilationBean.isCompilationTimeMonitoringSupported()) {
                out.put("totalCompilationTimeMs", compilationBean.getTotalCompilationTime());
            }
        }
        return out;
    }

    private static Map<String, Object> gaSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> pipeline = GAWorldgenPipelineStatus.snapshot();
        Map<String, Object> profiles = WorldgenProfileMetrics.snapshot();
        Map<String, Object> registryScan = WorldgenRegistryScanOrchestrator.GLOBAL.snapshot();
        Map<String, Object> scheduler = schedulerSnapshot();
        Map<String, Object> workspace = chunkWorkspaceSnapshot();
        Map<String, Object> commit = GACommitMetrics.snapshotGlobal();
        Map<String, Object> optimizer = WorldgenOptimizerMetrics.snapshot();
        Map<String, Object> outerLifecycle = GAOuterLifecycleMetrics.snapshotMap();
        out.put("modId", GeneratorAccelerator.MOD_ID);
        out.put("platform", GeneratorAccelerator.platform == null ? "unknown" : GeneratorAccelerator.platform.name());
        out.put("config", configSnapshot());
        out.put("adaptiveWorldgenPipeline", pipeline);
        out.put("featureVm", featureVmSnapshot());
        out.put("worldgenProfiles", profiles);
        out.put("worldgenRegistryScan", registryScan);
        out.put("scheduler", scheduler);
        out.put("chunkWorkspace", workspace);
        out.put("commitEngine", commit);
        out.put("patternOptimizer", optimizer);
        out.put("outerLifecycle", outerLifecycle);
        out.put("worldgenFeedback", GAWorldgenDiagnosticsFeedback.snapshot(
                profiles,
                registryScan,
                scheduler,
                workspace,
                commit,
                pipeline
        ));
        out.put("decorationPipeline", decorationPipelineSnapshot());
        out.put("noiseFill", noiseFillSnapshot());
        out.put("regionalWorldgen", regionalWorldgenSnapshot());
        out.put("surfaceCompiler", surfaceCompilerSnapshot());
        out.put("densityCompiler", densityCompilerSnapshot());
        return out;
    }

    private static Map<String, Object> configSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            GAConfig config = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
            for (Field field : GAConfig.class.getFields()) {
                out.put(field.getName(), field.get(config));
            }
        } catch (Throwable throwable) {
            out.put("error", throwable.toString());
        }
        return out;
    }

    private static Map<String, Object> featureVmSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", FeatureVmMetrics.ENABLED);
        out.put("programsCompiled", FeatureVmMetrics.programsCompiled());
        out.put("fastOpsCompiled", FeatureVmMetrics.fastOpsCompiled());
        out.put("fallbackOpsCompiled", FeatureVmMetrics.fallbackOpsCompiled());
        out.put("programExecutions", FeatureVmMetrics.programExecutions());
        out.put("linearFastExecutions", FeatureVmMetrics.linearFastExecutions());
        out.put("bufferFastExecutions", FeatureVmMetrics.bufferFastExecutions());
        out.put("fastOpExecutions", FeatureVmMetrics.fastOpExecutions());
        out.put("fallbackOpExecutions", FeatureVmMetrics.fallbackOpExecutions());
        out.put("featurePlaceCalls", FeatureVmMetrics.featurePlaceCalls());
        out.put("totalExecutionNanos", FeatureVmMetrics.totalExecutionNanos());
        out.put("summary", FeatureVmMetrics.summary());
        return out;
    }

    private static Map<String, Object> decorationPipelineSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", DecorationPipelineMetrics.ENABLED);
        long[] values = new long[DecorationPipelineMetrics.COUNTER_COUNT];
        DecorationPipelineMetrics.copyTo(values);
        Map<String, Object> counters = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            counters.put(DecorationPipelineMetrics.name(i), values[i]);
        }
        out.put("counters", counters);
        out.put("successfulWritesPerWorldRead", DecorationPipelineMetrics.successfulWritesPerWorldRead());
        out.put("summary", DecorationPipelineMetrics.summary());
        return out;
    }

    private static Map<String, Object> schedulerSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", GAScheduler.summary());
        out.putAll(GAScheduler.snapshot());
        out.put("customChunkGraph", GACustomChunkGraphScheduler.snapshot());
        return out;
    }

    private static Map<String, Object> noiseFillSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", GANoiseFillMetrics.ENABLED);
        long[] values = new long[GANoiseFillMetrics.COUNTER_COUNT];
        GANoiseFillMetrics.copyTo(values);
        Map<String, Object> counters = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            counters.put(GANoiseFillMetrics.name(i), values[i]);
        }
        out.put("counters", counters);
        out.put("snapshot", GANoiseFillMetrics.snapshot());
        out.put("summary", GANoiseFillMetrics.summary());
        return out;
    }

    private static Map<String, Object> regionalWorldgenSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("densitySlices", GARegionalDensitySliceCache.snapshot());
        out.put("aquiferAtlas", GARegionalAquiferAtlas.snapshot());
        out.put("beardifierAtlas", GARegionalBeardifierAtlas.snapshot());
        out.put("biomeQuartRaster", GARegionalBiomeSectionRaster.snapshot());
        out.put("climateQuartRaster", GARegionalClimateQuartRaster.snapshot());
        out.put("noiseBricks", GARegionalNoiseBrickCache.snapshot());
        out.put("prewarm", GARegionalPrewarmManager.snapshot());
        return out;
    }

    private static Map<String, Object> chunkWorkspaceSnapshot() {
        return GAChunkWorkspacePool.snapshot();
    }

    private static Map<String, Object> surfaceCompilerSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", SurfaceMetrics.ENABLED);
        out.put("compiledPrograms", SurfaceMetrics.compiledPrograms());
        out.put("irPrograms", SurfaceMetrics.irPrograms());
        out.put("irFallbacks", SurfaceMetrics.irFallbacks());
        out.put("irFallbackRuleNodes", SurfaceMetrics.irFallbackRuleNodes());
        out.put("irFallbackConditionNodes", SurfaceMetrics.irFallbackConditionNodes());
        out.put("interpretedPrograms", SurfaceMetrics.interpretedPrograms());
        out.put("optimizedPrograms", SurfaceMetrics.optimizedPrograms());
        out.put("cacheHits", SurfaceMetrics.cacheHits());
        out.put("cacheMisses", SurfaceMetrics.cacheMisses());
        out.put("lastEntryHits", SurfaceMetrics.lastEntryHits());
        out.put("unsupportedPrograms", SurfaceMetrics.unsupportedPrograms());
        out.put("unsupportedCacheHits", SurfaceMetrics.unsupportedCacheHits());
        out.put("vanillaFallbacks", SurfaceMetrics.vanillaFallbacks());
        out.put("sectionsProcessed", SurfaceMetrics.sectionsProcessed());
        out.put("emptySectionsSkipped", SurfaceMetrics.emptySectionsSkipped());
        out.put("rawBlockArrayMisses", SurfaceMetrics.rawBlockArrayMisses());
        out.put("stonelessSectionsSkipped", SurfaceMetrics.stonelessSectionsSkipped());
        out.put("fallbackIslands", SurfaceMetrics.fallbackIslands());
        out.put("conditionCacheHits", SurfaceMetrics.conditionCacheHits());
        out.put("conditionCacheMisses", SurfaceMetrics.conditionCacheMisses());
        out.put("activeMaskEarlyExits", SurfaceMetrics.activeMaskEarlyExits());

        Map<String, Object> nanos = new LinkedHashMap<>();
        nanos.put("cacheLookup", SurfaceMetrics.cacheLookupNanos());
        nanos.put("compile", SurfaceMetrics.compileNanos());
        nanos.put("biomePrep", SurfaceMetrics.biomePrepNanos());
        nanos.put("surfaceDepth", SurfaceMetrics.surfaceDepthNanos());
        nanos.put("secondarySurface", SurfaceMetrics.secondarySurfaceNanos());
        nanos.put("preliminarySurface", SurfaceMetrics.preliminarySurfaceNanos());
        nanos.put("stoneDepth", SurfaceMetrics.stoneDepthNanos());
        nanos.put("stoneMaskLoad", SurfaceMetrics.stoneMaskLoadNanos());
        nanos.put("programApply", SurfaceMetrics.programApplyNanos());
        nanos.put("fluidPostprocess", SurfaceMetrics.fluidPostprocessNanos());
        nanos.put("frozenOcean", SurfaceMetrics.frozenOceanNanos());
        nanos.put("fallbackRuleBridge", SurfaceMetrics.fallbackRuleBridgeNanos());
        nanos.put("fallbackConditionBridge", SurfaceMetrics.fallbackConditionBridgeNanos());
        out.put("nanos", nanos);

        Map<String, Object> conditions = new TreeMap<>();
        for (String kind : SurfaceMetrics.conditionKinds()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", SurfaceMetrics.conditionEvalCount(kind));
            item.put("nanos", SurfaceMetrics.conditionEvalNanos(kind));
            conditions.put(kind, item);
        }
        out.put("conditions", conditions);
        return out;
    }

    private static Map<String, Object> densityCompilerSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cellFill", DfcCellFillStats.snapshot());
        out.put("nativePlanning", DfcNativePlanningStats.snapshot());
        out.put("spline", DfcSplineStats.snapshot());
        out.put("splineTopClasses", DfcSplineStats.snapshotTopClasses(12));
        return out;
    }

    private static Map<String, Object> filteredProperties() {
        Properties properties = System.getProperties();
        TreeMap<String, Object> out = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (isRelevantProperty(name)) {
                out.put(name, properties.getProperty(name));
            }
        }
        return out;
    }

    private static boolean isRelevantProperty(String name) {
        return name.startsWith("ga.")
                || name.startsWith("dfc.")
                || name.startsWith("minecraft.")
                || name.startsWith("fabric.")
                || name.startsWith("neoforge.")
                || name.equals("java.version")
                || name.equals("java.vm.name")
                || name.equals("os.name")
                || name.equals("os.arch")
                || name.equals("user.dir");
    }

    private static Map<String, Object> memoryUsage(MemoryUsage usage) {
        if (usage == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initBytes", usage.getInit());
        out.put("usedBytes", usage.getUsed());
        out.put("committedBytes", usage.getCommitted());
        out.put("maxBytes", usage.getMax());
        return out;
    }

    private static GcTotals captureGcTotals() {
        long count = 0L;
        long timeMs = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long collectorCount = collector.getCollectionCount();
            long collectorTime = collector.getCollectionTime();
            if (collectorCount > 0L) {
                count += collectorCount;
            }
            if (collectorTime > 0L) {
                timeMs += collectorTime;
            }
        }
        return new GcTotals(count, timeMs);
    }

    private static Recording createRecording() throws Exception {
        String settings = System.getProperty("ga.diagnostics.jfr.settings", "profile");
        try {
            return new Recording(Configuration.getConfiguration(settings));
        } catch (IOException | java.text.ParseException | IllegalArgumentException e) {
            GeneratorAccelerator.LOGGER.warn("GA diagnostics JFR settings '{}' unavailable, using empty recording", settings, e);
            return new Recording();
        }
    }

    private static void configureRecording(Recording recording, boolean allocationStacks) {
        int sampleMs = Integer.getInteger("ga.diagnostics.jfr.sampleMs", 20);
        if (sampleMs > 0) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(sampleMs));
            recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(sampleMs));
        }
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.GCHeapSummary");
        recording.enable("jdk.GCPhasePause");
        recording.enable("jdk.YoungGarbageCollection");
        recording.enable("jdk.OldGarbageCollection");

        if (Boolean.getBoolean("ga.diagnostics.jfr.allocationSamples")) {
            recording.enable("jdk.ObjectAllocationSample").withStackTrace();
        }
        if (allocationStacks) {
            recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace().withoutThreshold();
            recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace().withoutThreshold();
        }
    }

    private static Path dumpActiveJfrSnapshot(String reason) {
        synchronized (JFR_LOCK) {
            Recording recording = activeRecording;
            if (recording == null || recording.getState() != RecordingState.RUNNING) {
                return null;
            }
            try {
                Files.createDirectories(dumpDir());
                Path path = dumpDir().resolve(filePrefix(reason) + ".jfr");
                recording.dump(path);
                GeneratorAccelerator.LOGGER.info("GA diagnostics JFR snapshot dumped after {}: {}", reason, path.toAbsolutePath());
                return path;
            } catch (Throwable throwable) {
                GeneratorAccelerator.LOGGER.warn("GA diagnostics failed to dump JFR snapshot", throwable);
                return null;
            }
        }
    }

    private static Path stopJfrAndDump(String reason) {
        synchronized (JFR_LOCK) {
            Recording recording = activeRecording;
            Path path = activeJfrPath;
            if (recording == null || path == null) {
                return null;
            }
            activeRecording = null;
            activeJfrPath = null;
            try {
                if (recording.getState() == RecordingState.RUNNING) {
                    recording.stop();
                }
                recording.dump(path);
                GeneratorAccelerator.LOGGER.info("GA diagnostics JFR dumped after {}: {}", reason, path.toAbsolutePath());
                return path;
            } catch (Throwable throwable) {
                GeneratorAccelerator.LOGGER.warn("GA diagnostics failed to dump JFR", throwable);
                return null;
            } finally {
                recording.close();
            }
        }
    }

    private static Path dumpDir() {
        String configured = System.getProperty("ga.diagnostics.dumpDir");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("ga.benchmark.dumpDir", "benchmark-dumps");
        }
        return Path.of(configured);
    }

    private static Path writeZip(String reason, Path jsonPath, Path jfrPath) throws IOException {
        Path zipPath = dumpDir().resolve(filePrefix(reason) + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            addZipEntry(zip, jsonPath);
            addZipEntry(zip, jfrPath);
        }
        GeneratorAccelerator.LOGGER.info("GA diagnostics bundle written: {}", zipPath.toAbsolutePath());
        return zipPath;
    }

    private static void addZipEntry(ZipOutputStream zip, Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        zip.putNextEntry(new ZipEntry(path.getFileName().toString()));
        Files.copy(path, zip);
        zip.closeEntry();
    }

    private static String filePrefix(String reason) {
        return "ga-diagnostics-" + FILE_TIME.format(Instant.now()) + "-" + safe(reason);
    }

    private static String safe(String value) {
        String text = value == null || value.isBlank() ? "dump" : value;
        return text.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private record GcTotals(long collectionCount, long collectionTimeMs) {
    }

    public record DumpResult(Path jsonPath, Path jfrPath, Path zipPath, boolean recordingStopped) {
    }
}
