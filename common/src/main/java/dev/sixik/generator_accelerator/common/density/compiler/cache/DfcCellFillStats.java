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
    public static final boolean ENABLED = Boolean.getBoolean("dfc.cellfill.stats");

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

    private DfcCellFillStats() {
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
                        List<String> sourceFillerClasses) {
    }

    public static Stats snapshot() {
        return new Stats(CELL_SCALAR.sum(), CELL_COMPILED.sum(), CELL_NATIVE_SLAB_INNER.sum(), CELL_UNKNOWN.sum(),
                CELL_XZ_SLAB.sum(), COLUMNS_SCALAR.sum(), CELL_EXTERN_ACCUMULATE.sum(), CELL_EXTERN_SCALAR_RESIDUAL.sum(),
                COLUMNS_JAVA_BATCHED.sum(), COLUMNS_NATIVE_INNER.sum(), ENABLED,
                snapshotFastFillerClasses(), snapshotFastFillerDebugClasses(), snapshotSourceFillerClasses());
    }

    public static void recordCellFill(DfcCellFillAccess filler, DensityFunction sourceFiller) {
        if (!ENABLED) {
            return;
        }
        if (sourceFiller != null) {
            SOURCE_FILLER_CLASSES.computeIfAbsent(sourceFiller.getClass().getName(), ignored -> new LongAdder())
                    .increment();
        }
        if (filler instanceof CompiledDensityFunction compiled) {
            CELL_COMPILED.increment();
            boolean nativeSlabInner = compiled.dfc$hasNativeSlabInnerProgram();
            FAST_FILLER_CLASSES.computeIfAbsent(filler.getClass().getName(), ignored -> new ClassStatsCounter())
                    .record(nativeSlabInner);
            if (nativeSlabInner) {
                CELL_NATIVE_SLAB_INNER.increment();
            }
        } else {
            FAST_FILLER_CLASSES.computeIfAbsent(filler.getClass().getName(), ignored -> new ClassStatsCounter())
                    .record(false);
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

    public static void recordCellScalar() {
        CELL_SCALAR.increment();
    }

    public static void recordCellXzSlab() {
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

    public static void recordColumnScalar() {
        COLUMNS_SCALAR.increment();
    }

    public static void recordColumnJavaBatched() {
        COLUMNS_JAVA_BATCHED.increment();
    }

    public static void recordColumnNativeInner() {
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
}
