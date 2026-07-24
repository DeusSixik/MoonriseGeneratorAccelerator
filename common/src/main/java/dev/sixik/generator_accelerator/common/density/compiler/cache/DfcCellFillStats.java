package dev.sixik.generator_accelerator.common.density.compiler.cache;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadRuntimeRegistry;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime counters for generated cell-fill execution modes.
 */
public final class DfcCellFillStats {
    public static volatile boolean ENABLED = GAConfigHolder.getConfig().dfc.cellFillStats;
    public static volatile boolean RESIDUAL_CLASS_DEBUG_ENABLED =
            ENABLED && GAConfigHolder.getConfig().dfc.cellFillResidualClassDebug;

    private static final LongAdder CELL_SCALAR = new LongAdder();
    private static final LongAdder CELL_COMPILED = new LongAdder();
    private static final LongAdder CELL_UNKNOWN = new LongAdder();
    private static final LongAdder CELL_XZ_SLAB = new LongAdder();
    private static final LongAdder CELL_EXTERN_ACCUMULATE = new LongAdder();
    private static final LongAdder CELL_EXTERN_SCALAR_RESIDUAL = new LongAdder();
    private static final LongAdder CELL_GPU_PAYLOAD_READY = new LongAdder();
    private static final LongAdder CELL_GPU_PAYLOAD_BLOCKED = new LongAdder();
    private static final LongAdder COLUMNS_SCALAR = new LongAdder();
    private static final LongAdder COLUMNS_JAVA_BATCHED = new LongAdder();
    private static final ConcurrentHashMap<String, ClassStatsCounter> FAST_FILLER_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SOURCE_FILLER_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> RESIDUAL_EXTERN_FALLBACK_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> CELL_GPU_FIRST_BLOCKERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> CELL_GPU_UNSUPPORTED_NODES = new ConcurrentHashMap<>();

    private DfcCellFillStats() {
    }

    public static void setEnabled(boolean enabled, boolean residualClassDebugEnabled) {
        ENABLED = enabled;
        RESIDUAL_CLASS_DEBUG_ENABLED = enabled && residualClassDebugEnabled;
    }

    public record ClassStats(String className, long calls) {
    }

    public record ClassDebugStats(String className, long calls,
                                  String sourceRootClass, boolean latticeEmitted,
                                  boolean cellAddLatticeSpecialized,
                                  boolean cellAddBeardifierSpecialized,
                                  boolean cellAddExternSpecialized,
                                  String rootDebug) {
    }

    public record Stats(long cellScalar, long cellCompiled, long cellUnknown, long cellXzSlab, long columnsScalar,
                        long cellExternAccumulate, long cellExternScalarResidual,
                        long cellGpuPayloadReady, long cellGpuPayloadBlocked,
                        long columnsJavaBatched, boolean enabled,
                        List<ClassStats> fastFillerClasses, List<ClassDebugStats> fastFillerDebugClasses,
                        List<String> sourceFillerClasses,
                        List<String> residualExternFallbackClasses,
                        List<String> cellGpuFirstBlockers,
                        List<String> cellGpuUnsupportedNodes) {
    }

    public static Stats snapshot() {
        return new Stats(CELL_SCALAR.sum(), CELL_COMPILED.sum(), CELL_UNKNOWN.sum(),
                CELL_XZ_SLAB.sum(), COLUMNS_SCALAR.sum(), CELL_EXTERN_ACCUMULATE.sum(), CELL_EXTERN_SCALAR_RESIDUAL.sum(),
                CELL_GPU_PAYLOAD_READY.sum(), CELL_GPU_PAYLOAD_BLOCKED.sum(),
                COLUMNS_JAVA_BATCHED.sum(), ENABLED,
                snapshotFastFillerClasses(), snapshotFastFillerDebugClasses(), snapshotSourceFillerClasses(),
                snapshotResidualExternFallbackClasses(), snapshotCounts(CELL_GPU_FIRST_BLOCKERS),
                snapshotCounts(CELL_GPU_UNSUPPORTED_NODES));
    }

    public static void reset() {
        CELL_SCALAR.reset();
        CELL_COMPILED.reset();
        CELL_UNKNOWN.reset();
        CELL_XZ_SLAB.reset();
        CELL_EXTERN_ACCUMULATE.reset();
        CELL_EXTERN_SCALAR_RESIDUAL.reset();
        CELL_GPU_PAYLOAD_READY.reset();
        CELL_GPU_PAYLOAD_BLOCKED.reset();
        COLUMNS_SCALAR.reset();
        COLUMNS_JAVA_BATCHED.reset();
        FAST_FILLER_CLASSES.clear();
        SOURCE_FILLER_CLASSES.clear();
        RESIDUAL_EXTERN_FALLBACK_CLASSES.clear();
        CELL_GPU_FIRST_BLOCKERS.clear();
        CELL_GPU_UNSUPPORTED_NODES.clear();
    }

    public static void recordCellFill(DfcCellFillAccess filler, DensityFunction sourceFiller) {
        if (!ENABLED) {
            return;
        }
        if (sourceFiller != null) {
            LongAdder sourceCounter = trackedLongAdder(SOURCE_FILLER_CLASSES, sourceFiller.getClass().getName());
            if (sourceCounter != null) {
                sourceCounter.increment();
            }
        }
        if (filler instanceof CompiledDensityFunction) {
            CELL_COMPILED.increment();
            recordGpuDiagnostics((CompiledDensityFunction) filler);
            ClassStatsCounter fastCounter = trackedClassStatsCounter(filler.getClass().getName());
            if (fastCounter != null) {
                fastCounter.record();
            }
        } else {
            ClassStatsCounter fastCounter = trackedClassStatsCounter(filler.getClass().getName());
            if (fastCounter != null) {
                fastCounter.record();
            }
            CELL_UNKNOWN.increment();
        }
    }

    private static List<ClassStats> snapshotFastFillerClasses() {
        List<ClassStats> out = new ArrayList<>(FAST_FILLER_CLASSES.size());
        FAST_FILLER_CLASSES.forEach((name, counter) ->
                out.add(new ClassStats(name, counter.calls.sum())));
        out.sort(Comparator.comparingLong(ClassStats::calls).reversed().thenComparing(ClassStats::className));
        if (out.size() > 8) {
            return new ArrayList<>(out.subList(0, 8));
        }
        return out;
    }

    private static List<ClassDebugStats> snapshotFastFillerDebugClasses() {
        List<ClassDebugStats> out = new ArrayList<>(FAST_FILLER_CLASSES.size());
        FAST_FILLER_CLASSES.forEach((name, counter) -> {
            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(name);
            out.add(new ClassDebugStats(
                    name,
                    counter.calls.sum(),
                    entry != null ? entry.sourceRootClass() : "unknown",
                    entry != null && entry.latticeEmitted(),
                    entry != null && entry.cellAddLatticeSpecialized(),
                    entry != null && entry.cellAddBeardifierSpecialized(),
                    entry != null && entry.cellAddExternSpecialized(),
                    entry != null ? entry.rootDebug() : "unknown"
            ));
        });
        out.sort(Comparator.comparingLong(ClassDebugStats::calls).reversed().thenComparing(ClassDebugStats::className));
        if (out.size() > 8) {
            return new ArrayList<>(out.subList(0, 8));
        }
        return out;
    }

    private static List<String> snapshotSourceFillerClasses() {
        List<SourceClassStats> raw = new ArrayList<>(SOURCE_FILLER_CLASSES.size());
        SOURCE_FILLER_CLASSES.forEach((name, counter) -> raw.add(new SourceClassStats(name, counter.sum())));
        raw.sort(Comparator.comparingLong(SourceClassStats::calls).reversed().thenComparing(SourceClassStats::className));
        List<String> out = new ArrayList<>(raw.size());
        for (SourceClassStats stat : raw) {
            out.add(stat.className() + "=" + stat.calls());
        }
        if (out.size() > 8) {
            return new ArrayList<>(out.subList(0, 8));
        }
        return out;
    }

    private static List<String> snapshotResidualExternFallbackClasses() {
        return snapshotCounts(RESIDUAL_EXTERN_FALLBACK_CLASSES);
    }

    private static List<String> snapshotCounts(ConcurrentHashMap<String, LongAdder> counts) {
        List<SourceClassStats> raw = new ArrayList<>(counts.size());
        counts.forEach((name, counter) -> raw.add(new SourceClassStats(name, counter.sum())));
        raw.sort(Comparator.comparingLong(SourceClassStats::calls).reversed().thenComparing(SourceClassStats::className));
        List<String> out = new ArrayList<>(raw.size());
        for (SourceClassStats stat : raw) {
            out.add(stat.className() + "=" + stat.calls());
        }
        if (out.size() > 8) {
            return new ArrayList<>(out.subList(0, 8));
        }
        return out;
    }

    private static void recordGpuDiagnostics(CompiledDensityFunction filler) {
        GpuPayloadRuntimeRegistry.Diagnostics diagnostics = GpuPayloadRuntimeRegistry.diagnostics(filler);
        if (diagnostics == null) {
            increment(CELL_GPU_UNSUPPORTED_NODES, "missing-diagnostics");
            CELL_GPU_PAYLOAD_BLOCKED.increment();
            return;
        }
        if (diagnostics.payloadReady()) {
            CELL_GPU_PAYLOAD_READY.increment();
            return;
        }
        CELL_GPU_PAYLOAD_BLOCKED.increment();
        increment(CELL_GPU_FIRST_BLOCKERS, diagnostics.firstEligibilityBlocker());
        increment(CELL_GPU_UNSUPPORTED_NODES, diagnostics.firstUnsupportedDetail());
    }

    private static void increment(ConcurrentHashMap<String, LongAdder> counts, String key) {
        if (key == null || key.isBlank() || "none".equals(key)) {
            return;
        }
        counts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    public static void recordCellScalar() {
        if (!ENABLED) {
            return;
        }
        CELL_SCALAR.increment();
    }

    public static void recordCellXzSlab() {
        if (!ENABLED) {
            return;
        }
        CELL_XZ_SLAB.increment();
    }

    public static void recordCellExternAccumulate() {
        if (!ENABLED) {
            return;
        }
        CELL_EXTERN_ACCUMULATE.increment();
    }

    public static void recordCellExternScalarResidual() {
        if (!ENABLED) {
            return;
        }
        CELL_EXTERN_SCALAR_RESIDUAL.increment();
    }

    public static void recordCellExternScalarResidualClass(Object residualExtern) {
        if (!RESIDUAL_CLASS_DEBUG_ENABLED || residualExtern == null) {
            return;
        }
        LongAdder counter = trackedLongAdder(RESIDUAL_EXTERN_FALLBACK_CLASSES, residualExtern.getClass().getName());
        if (counter != null) {
            counter.increment();
        }
    }

    public static void recordColumnScalar() {
        if (!ENABLED) {
            return;
        }
        COLUMNS_SCALAR.increment();
    }

    public static void recordColumnJavaBatched() {
        if (!ENABLED) {
            return;
        }
        COLUMNS_JAVA_BATCHED.increment();
    }

    private static final class ClassStatsCounter {
        private final LongAdder calls = new LongAdder();

        private void record() {
            calls.increment();
        }
    }

    private record SourceClassStats(String className, long calls) {
    }

    private static ClassStatsCounter trackedClassStatsCounter(String className) {
        ClassStatsCounter existing = FAST_FILLER_CLASSES.get(className);
        if (existing != null) {
            return existing;
        }
        int maxTrackedClasses = Math.max(1, GAConfigHolder.getConfig().dfc.cellFillStatsMaxTrackedClasses);
        if (FAST_FILLER_CLASSES.size() >= maxTrackedClasses) {
            return null;
        }
        return FAST_FILLER_CLASSES.computeIfAbsent(className, ignored -> new ClassStatsCounter());
    }

    private static LongAdder trackedLongAdder(ConcurrentHashMap<String, LongAdder> map, String className) {
        LongAdder existing = map.get(className);
        if (existing != null) {
            return existing;
        }
        int maxTrackedClasses = Math.max(1, GAConfigHolder.getConfig().dfc.cellFillStatsMaxTrackedClasses);
        if (map.size() >= maxTrackedClasses) {
            return null;
        }
        return map.computeIfAbsent(className, ignored -> new LongAdder());
    }
}
