package dev.sixik.generator_accelerator.common.density.compiler.cache;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
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
    public static volatile boolean ENABLED = Boolean.getBoolean("dfc.cellfill.stats");
    public static volatile boolean RESIDUAL_CLASS_DEBUG_ENABLED =
            ENABLED && Boolean.getBoolean("dfc.cellfill.stats.residualClassDebug");
    private static final int MAX_TRACKED_CLASSES = Integer.getInteger("ga.dfc.cellFillStats.maxTrackedClasses", 256);

    private static final LongAdder CELL_SCALAR = new LongAdder();
    private static final LongAdder CELL_COMPILED = new LongAdder();
    private static final LongAdder CELL_NATIVE_SLAB_INNER = new LongAdder();
    private static final LongAdder CELL_UNKNOWN = new LongAdder();
    private static final LongAdder CELL_XZ_SLAB = new LongAdder();
    private static final LongAdder CELL_EXTERN_ACCUMULATE = new LongAdder();
    private static final LongAdder CELL_EXTERN_SCALAR_RESIDUAL = new LongAdder();
    private static final LongAdder COLUMNS_SCALAR = new LongAdder();
    private static final LongAdder COLUMNS_JAVA_BATCHED = new LongAdder();
    private static final LongAdder COLUMNS_NATIVE_INNER = new LongAdder();
    private static final ConcurrentHashMap<String, ClassStatsCounter> FAST_FILLER_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SOURCE_FILLER_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> RESIDUAL_EXTERN_FALLBACK_CLASSES = new ConcurrentHashMap<>();

    private DfcCellFillStats() {
    }

    public static void setEnabled(boolean enabled, boolean residualClassDebugEnabled) {
        ENABLED = enabled;
        RESIDUAL_CLASS_DEBUG_ENABLED = enabled && residualClassDebugEnabled;
    }

    public record ClassStats(String className, long calls, long nativeSlabInnerCalls) {
    }

    public record ClassDebugStats(String className, long calls, long nativeSlabInnerCalls,
                                  String sourceRootClass, boolean latticeEmitted,
                                  boolean slabInnerProgramPresent,
                                  boolean cellAddLatticeSpecialized,
                                  boolean cellAddExternSpecialized,
                                  String rootDebug) {
    }

    public record Stats(long cellScalar, long cellCompiled, long cellNativeSlabInner, long cellUnknown, long cellXzSlab, long columnsScalar,
                        long cellExternAccumulate, long cellExternScalarResidual,
                        long columnsJavaBatched, long columnsNativeInner, boolean enabled,
                        List<ClassStats> fastFillerClasses, List<ClassDebugStats> fastFillerDebugClasses,
                        List<String> sourceFillerClasses,
                        List<String> residualExternFallbackClasses) {
    }

    public static Stats snapshot() {
        return new Stats(CELL_SCALAR.sum(), CELL_COMPILED.sum(), CELL_NATIVE_SLAB_INNER.sum(), CELL_UNKNOWN.sum(),
                CELL_XZ_SLAB.sum(), COLUMNS_SCALAR.sum(), CELL_EXTERN_ACCUMULATE.sum(), CELL_EXTERN_SCALAR_RESIDUAL.sum(),
                COLUMNS_JAVA_BATCHED.sum(), COLUMNS_NATIVE_INNER.sum(), ENABLED,
                snapshotFastFillerClasses(), snapshotFastFillerDebugClasses(), snapshotSourceFillerClasses(),
                snapshotResidualExternFallbackClasses());
    }

    public static void reset() {
        CELL_SCALAR.reset();
        CELL_COMPILED.reset();
        CELL_NATIVE_SLAB_INNER.reset();
        CELL_UNKNOWN.reset();
        CELL_XZ_SLAB.reset();
        CELL_EXTERN_ACCUMULATE.reset();
        CELL_EXTERN_SCALAR_RESIDUAL.reset();
        COLUMNS_SCALAR.reset();
        COLUMNS_JAVA_BATCHED.reset();
        COLUMNS_NATIVE_INNER.reset();
        FAST_FILLER_CLASSES.clear();
        SOURCE_FILLER_CLASSES.clear();
        RESIDUAL_EXTERN_FALLBACK_CLASSES.clear();
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
        if (filler instanceof CompiledDensityFunction compiled) {
            CELL_COMPILED.increment();
            boolean nativeSlabInner = compiled.dfc$hasNativeSlabInnerProgram();
            ClassStatsCounter fastCounter = trackedClassStatsCounter(filler.getClass().getName());
            if (fastCounter != null) {
                fastCounter.record(nativeSlabInner);
            }
            if (nativeSlabInner) {
                CELL_NATIVE_SLAB_INNER.increment();
            }
        } else {
            ClassStatsCounter fastCounter = trackedClassStatsCounter(filler.getClass().getName());
            if (fastCounter != null) {
                fastCounter.record(false);
            }
            CELL_UNKNOWN.increment();
        }
    }

    private static List<ClassStats> snapshotFastFillerClasses() {
        List<ClassStats> out = new ArrayList<>(FAST_FILLER_CLASSES.size());
        FAST_FILLER_CLASSES.forEach((name, counter) ->
                out.add(new ClassStats(name, counter.calls.sum(), counter.nativeSlabInnerCalls.sum())));
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
                    counter.nativeSlabInnerCalls.sum(),
                    entry != null ? entry.sourceRootClass() : "unknown",
                    entry != null && entry.latticeEmitted(),
                    entry != null && entry.slabInnerProgramPresent(),
                    entry != null && entry.cellAddLatticeSpecialized(),
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
        List<SourceClassStats> raw = new ArrayList<>(RESIDUAL_EXTERN_FALLBACK_CLASSES.size());
        RESIDUAL_EXTERN_FALLBACK_CLASSES.forEach((name, counter) -> raw.add(new SourceClassStats(name, counter.sum())));
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

    public static void recordColumnNativeInner() {
        if (!ENABLED) {
            return;
        }
        COLUMNS_NATIVE_INNER.increment();
    }

    private static final class ClassStatsCounter {
        private final LongAdder calls = new LongAdder();
        private final LongAdder nativeSlabInnerCalls = new LongAdder();

        private void record(boolean nativeSlabInner) {
            calls.increment();
            if (nativeSlabInner) {
                nativeSlabInnerCalls.increment();
            }
        }
    }

    private record SourceClassStats(String className, long calls) {
    }

    private static ClassStatsCounter trackedClassStatsCounter(String className) {
        ClassStatsCounter existing = FAST_FILLER_CLASSES.get(className);
        if (existing != null) {
            return existing;
        }
        if (FAST_FILLER_CLASSES.size() >= MAX_TRACKED_CLASSES) {
            return null;
        }
        return FAST_FILLER_CLASSES.computeIfAbsent(className, ignored -> new ClassStatsCounter());
    }

    private static LongAdder trackedLongAdder(ConcurrentHashMap<String, LongAdder> map, String className) {
        LongAdder existing = map.get(className);
        if (existing != null) {
            return existing;
        }
        if (map.size() >= MAX_TRACKED_CLASSES) {
            return null;
        }
        return map.computeIfAbsent(className, ignored -> new LongAdder());
    }
}
