package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead runtime telemetry for the DensityFunction compiler.
 *
 * <p>The hot-path timers are opt-in and sampled. When disabled, generated code keeps
 * the old direct call shape unless a call site explicitly routes through a DFC helper
 * such as {@link DfcCacheFastPath}.
 */
public final class DfcRuntimeTelemetry {
    public static volatile boolean ENABLED = Boolean.getBoolean("dfc.telemetry.enabled");

    private static final int SAMPLE_SHIFT = Math.max(0,
            Integer.getInteger("dfc.telemetry.sampleShift", 7));
    private static final long SAMPLE_MASK = (1L << Math.min(SAMPLE_SHIFT, 30)) - 1L;
    private static final long SAMPLE_SCALE = 1L << Math.min(SAMPLE_SHIFT, 30);
    private static final boolean PRECISE_HOT_COUNTERS = Boolean.getBoolean("dfc.telemetry.preciseHotCounters");
    private static final int MAX_TRACKED_CLASSES = Math.max(1,
            Integer.getInteger("dfc.telemetry.maxTrackedClasses", 256));

    private static final LongAdder COMPILED_COMPUTE_CALLS = new LongAdder();
    private static final LongAdder COMPILED_COMPUTE_SAMPLES = new LongAdder();
    private static final LongAdder COMPILED_COMPUTE_NANOS = new LongAdder();
    private static final LongAdder FILL_ARRAY_CALLS = new LongAdder();
    private static final LongAdder FILL_ARRAY_SAMPLES = new LongAdder();
    private static final LongAdder FILL_ARRAY_NANOS = new LongAdder();
    private static final LongAdder FILL_CELL_CALLS = new LongAdder();
    private static final LongAdder FILL_CELL_SAMPLES = new LongAdder();
    private static final LongAdder FILL_CELL_NANOS = new LongAdder();
    private static final LongAdder ACCUMULATE_CELL_CALLS = new LongAdder();
    private static final LongAdder ACCUMULATE_CELL_SAMPLES = new LongAdder();
    private static final LongAdder ACCUMULATE_CELL_NANOS = new LongAdder();
    private static final LongAdder EXTERN_INVOKE_CALLS = new LongAdder();
    private static final LongAdder EXTERN_INVOKE_SAMPLES = new LongAdder();
    private static final LongAdder EXTERN_INVOKE_NANOS = new LongAdder();
    private static final LongAdder MARKER_INVOKE_CALLS = new LongAdder();
    private static final LongAdder MARKER_INVOKE_SAMPLES = new LongAdder();
    private static final LongAdder MARKER_INVOKE_NANOS = new LongAdder();
    private static final LongAdder CACHE_FAST_PATH_CALLS = new LongAdder();
    private static final LongAdder CACHE_FAST_PATH_SAMPLES = new LongAdder();
    private static final LongAdder CACHE_FAST_PATH_NANOS = new LongAdder();
    private static final LongAdder COMPILE_ROOTS = new LongAdder();
    private static final LongAdder COMPILE_FAILURES = new LongAdder();
    private static final LongAdder COMPILE_SAMPLES = new LongAdder();
    private static final LongAdder COMPILE_NANOS = new LongAdder();
    private static final LongAdder COMPILE_QUEUE_WAITS = new LongAdder();
    private static final LongAdder COMPILE_QUEUE_WAIT_NANOS = new LongAdder();

    private static final ThreadLocal<SampleState> SAMPLE_STATE = ThreadLocal.withInitial(SampleState::new);
    private static final ConcurrentHashMap<String, ClassCounter> EXTERN_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ClassCounter> MARKER_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ClassCounter> GENERATED_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ClassCounter> FALLBACK_CLASSES = new ConcurrentHashMap<>();

    private DfcRuntimeTelemetry() {
    }

    public record ClassStats(String className, long calls, long sampledNanos) {
    }

    public record GeneratedClassDebugStats(String className, long calls, long sampledNanos,
                                           String sourceRootClass, boolean latticeEmitted,
                                           boolean slabInnerProgramPresent,
                                           boolean cellAddLatticeSpecialized,
                                           boolean cellAddExternSpecialized,
                                           String rootDebug) {
    }

    public record Stats(boolean enabled, int sampleShift,
                        long compiledComputeCalls, long compiledComputeSamples, long compiledComputeNanos,
                        long fillArrayCalls, long fillArraySamples, long fillArrayNanos,
                        long fillCellCalls, long fillCellSamples, long fillCellNanos,
                        long accumulateCellCalls, long accumulateCellSamples, long accumulateCellNanos,
                        long externInvokeCalls, long externInvokeSamples, long externInvokeNanos,
                        long markerInvokeCalls, long markerInvokeSamples, long markerInvokeNanos,
                        long cacheFastPathCalls, long cacheFastPathSamples, long cacheFastPathNanos,
                        long compileRoots, long compileFailures, long compileSamples, long compileNanos,
                        long compileQueueWaits, long compileQueueWaitNanos,
                        List<ClassStats> topExternClasses,
                        List<ClassStats> topMarkerClasses,
                        List<ClassStats> topGeneratedClasses,
                        List<ClassStats> topFallbackClasses) {
        public double estimatedCompiledComputeNanos() {
            return estimate(compiledComputeCalls, compiledComputeSamples, compiledComputeNanos);
        }

        public double estimatedExternInvokeNanos() {
            return estimate(externInvokeCalls, externInvokeSamples, externInvokeNanos);
        }

        public double estimatedMarkerInvokeNanos() {
            return estimate(markerInvokeCalls, markerInvokeSamples, markerInvokeNanos);
        }

        public double estimatedFillArrayNanos() {
            return estimate(fillArrayCalls, fillArraySamples, fillArrayNanos);
        }

        private static double estimate(long calls, long samples, long nanos) {
            if (calls <= 0L || samples <= 0L || nanos <= 0L) {
                return 0.0D;
            }
            return nanos * (calls / (double) samples);
        }
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean preciseHotCounters() {
        return PRECISE_HOT_COUNTERS;
    }

    public static long sampleStart() {
        if (!ENABLED) {
            return 0L;
        }
        long sequence = ++SAMPLE_STATE.get().sequence;
        if ((sequence & SAMPLE_MASK) != 0L) {
            return 0L;
        }
        return System.nanoTime();
    }

    public static void recordCompiledCompute(Class<?> generatedClass, long startedAt) {
        if (!ENABLED) {
            return;
        }
        long elapsed = elapsedSince(startedAt);
        recordHotCall(COMPILED_COMPUTE_CALLS, elapsed);
        recordClass(GENERATED_CLASSES, generatedClass == null ? "unknown" : generatedClass.getName(), elapsed, false);
        if (elapsed != 0L) {
            COMPILED_COMPUTE_SAMPLES.increment();
            COMPILED_COMPUTE_NANOS.add(elapsed);
        }
    }

    public static void recordFillArray(Class<?> generatedClass, long startedAt) {
        if (!ENABLED) {
            return;
        }
        FILL_ARRAY_CALLS.increment();
        long elapsed = elapsedSince(startedAt);
        recordClass(GENERATED_CLASSES, generatedClass == null ? "unknown" : generatedClass.getName(), elapsed, false);
        if (elapsed != 0L) {
            FILL_ARRAY_SAMPLES.increment();
            FILL_ARRAY_NANOS.add(elapsed);
        }
    }

    public static void recordFillCell(Class<?> generatedClass, long startedAt) {
        if (!ENABLED) {
            return;
        }
        FILL_CELL_CALLS.increment();
        long elapsed = elapsedSince(startedAt);
        recordClass(GENERATED_CLASSES, generatedClass == null ? "unknown" : generatedClass.getName(), elapsed, false);
        if (elapsed != 0L) {
            FILL_CELL_SAMPLES.increment();
            FILL_CELL_NANOS.add(elapsed);
        }
    }

    public static void recordAccumulateCell(Class<?> generatedClass, long startedAt) {
        if (!ENABLED) {
            return;
        }
        ACCUMULATE_CELL_CALLS.increment();
        long elapsed = elapsedSince(startedAt);
        recordClass(GENERATED_CLASSES, generatedClass == null ? "unknown" : generatedClass.getName(), elapsed, false);
        if (elapsed != 0L) {
            ACCUMULATE_CELL_SAMPLES.increment();
            ACCUMULATE_CELL_NANOS.add(elapsed);
        }
    }

    public static double computeExtern(DensityFunction extern, DensityFunction.FunctionContext context) {
        if (!ENABLED) {
            return extern.compute(context);
        }
        long startedAt = sampleStart();
        try {
            return extern.compute(context);
        } finally {
            long elapsed = elapsedSince(startedAt);
            EXTERN_INVOKE_CALLS.increment();
            recordClass(EXTERN_CLASSES, extern == null ? "null" : extern.getClass().getName(), elapsed, false);
            if (elapsed != 0L) {
                EXTERN_INVOKE_SAMPLES.increment();
                EXTERN_INVOKE_NANOS.add(elapsed);
            }
        }
    }

    public static double computeMarkerExtern(DensityFunction extern, DensityFunction.FunctionContext context) {
        if (!ENABLED) {
            return extern.compute(context);
        }
        long startedAt = sampleStart();
        try {
            return extern.compute(context);
        } finally {
            long elapsed = elapsedSince(startedAt);
            MARKER_INVOKE_CALLS.increment();
            recordClass(MARKER_CLASSES, extern == null ? "null" : extern.getClass().getName(), elapsed, false);
            if (elapsed != 0L) {
                MARKER_INVOKE_SAMPLES.increment();
                MARKER_INVOKE_NANOS.add(elapsed);
            }
        }
    }

    public static double computeMarkerFastPath(DensityFunction extern, DensityFunction.FunctionContext context) {
        if (!ENABLED) {
            return DfcCacheFastPath.computeWithOptionalDirectRead(extern, context);
        }
        long startedAt = sampleStart();
        try {
            return DfcCacheFastPath.computeWithOptionalDirectRead(extern, context);
        } finally {
            long elapsed = elapsedSince(startedAt);
            MARKER_INVOKE_CALLS.increment();
            CACHE_FAST_PATH_CALLS.increment();
            recordClass(MARKER_CLASSES, extern == null ? "null" : extern.getClass().getName(), elapsed, false);
            if (elapsed != 0L) {
                MARKER_INVOKE_SAMPLES.increment();
                MARKER_INVOKE_NANOS.add(elapsed);
                CACHE_FAST_PATH_SAMPLES.increment();
                CACHE_FAST_PATH_NANOS.add(elapsed);
            }
        }
    }

    public static double computeMarkerFastPath(DensityFunction extern, NoiseChunk chunk) {
        if (!ENABLED) {
            return DfcCacheFastPath.computeWithOptionalDirectRead(extern, chunk);
        }
        long startedAt = sampleStart();
        try {
            return DfcCacheFastPath.computeWithOptionalDirectRead(extern, chunk);
        } finally {
            long elapsed = elapsedSince(startedAt);
            MARKER_INVOKE_CALLS.increment();
            CACHE_FAST_PATH_CALLS.increment();
            recordClass(MARKER_CLASSES, extern == null ? "null" : extern.getClass().getName(), elapsed, false);
            if (elapsed != 0L) {
                MARKER_INVOKE_SAMPLES.increment();
                MARKER_INVOKE_NANOS.add(elapsed);
                CACHE_FAST_PATH_SAMPLES.increment();
                CACHE_FAST_PATH_NANOS.add(elapsed);
            }
        }
    }

    public static double computeMarkerTrustedFastPath(
            DfcCellCacheAccess access, DensityFunction extern, NoiseChunk chunk) {
        if (!ENABLED) {
            return DfcCacheFastPath.computeTrustedDirectRead(access, extern, chunk);
        }
        long startedAt = sampleStart();
        try {
            return DfcCacheFastPath.computeTrustedDirectRead(access, extern, chunk);
        } finally {
            long elapsed = elapsedSince(startedAt);
            MARKER_INVOKE_CALLS.increment();
            CACHE_FAST_PATH_CALLS.increment();
            recordClass(MARKER_CLASSES, extern == null ? "null" : extern.getClass().getName(), elapsed, false);
            if (elapsed != 0L) {
                MARKER_INVOKE_SAMPLES.increment();
                MARKER_INVOKE_NANOS.add(elapsed);
                CACHE_FAST_PATH_SAMPLES.increment();
                CACHE_FAST_PATH_NANOS.add(elapsed);
            }
        }
    }

    public static void recordCompileStartQueueWait(long submittedAtNanos) {
        if (!ENABLED || submittedAtNanos == 0L) {
            return;
        }
        COMPILE_QUEUE_WAITS.increment();
        COMPILE_QUEUE_WAIT_NANOS.add(System.nanoTime() - submittedAtNanos);
    }

    public static long compileStart() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordCompileEnd(Class<?> sourceClass, boolean success, long startedAt) {
        if (!ENABLED) {
            return;
        }
        COMPILE_ROOTS.increment();
        if (!success) {
            COMPILE_FAILURES.increment();
            if (sourceClass != null) {
                recordClass(FALLBACK_CLASSES, sourceClass.getName(), 0L, true);
            }
        }
        if (startedAt != 0L) {
            COMPILE_SAMPLES.increment();
            COMPILE_NANOS.add(System.nanoTime() - startedAt);
        }
    }

    public static Stats snapshot() {
        return new Stats(
                ENABLED,
                SAMPLE_SHIFT,
                COMPILED_COMPUTE_CALLS.sum(), COMPILED_COMPUTE_SAMPLES.sum(), COMPILED_COMPUTE_NANOS.sum(),
                FILL_ARRAY_CALLS.sum(), FILL_ARRAY_SAMPLES.sum(), FILL_ARRAY_NANOS.sum(),
                FILL_CELL_CALLS.sum(), FILL_CELL_SAMPLES.sum(), FILL_CELL_NANOS.sum(),
                ACCUMULATE_CELL_CALLS.sum(), ACCUMULATE_CELL_SAMPLES.sum(), ACCUMULATE_CELL_NANOS.sum(),
                EXTERN_INVOKE_CALLS.sum(), EXTERN_INVOKE_SAMPLES.sum(), EXTERN_INVOKE_NANOS.sum(),
                MARKER_INVOKE_CALLS.sum(), MARKER_INVOKE_SAMPLES.sum(), MARKER_INVOKE_NANOS.sum(),
                CACHE_FAST_PATH_CALLS.sum(), CACHE_FAST_PATH_SAMPLES.sum(), CACHE_FAST_PATH_NANOS.sum(),
                COMPILE_ROOTS.sum(), COMPILE_FAILURES.sum(), COMPILE_SAMPLES.sum(), COMPILE_NANOS.sum(),
                COMPILE_QUEUE_WAITS.sum(), COMPILE_QUEUE_WAIT_NANOS.sum(),
                snapshotClasses(EXTERN_CLASSES),
                snapshotClasses(MARKER_CLASSES),
                snapshotClasses(GENERATED_CLASSES),
                snapshotClasses(FALLBACK_CLASSES));
    }

    public static List<GeneratedClassDebugStats> snapshotTopGeneratedDebugClasses() {
        List<GeneratedClassDebugStats> out = new ArrayList<>();
        for (ClassStats stats : snapshotClasses(GENERATED_CLASSES)) {
            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(stats.className());
            out.add(new GeneratedClassDebugStats(
                    stats.className(),
                    stats.calls(),
                    stats.sampledNanos(),
                    entry != null ? entry.sourceRootClass() : "unknown",
                    entry != null && entry.latticeEmitted(),
                    entry != null && entry.slabInnerProgramPresent(),
                    entry != null && entry.cellAddLatticeSpecialized(),
                    entry != null && entry.cellAddExternSpecialized(),
                    entry != null ? entry.rootDebug() : "unknown"));
        }
        return out;
    }

    public static void reset() {
        COMPILED_COMPUTE_CALLS.reset();
        COMPILED_COMPUTE_SAMPLES.reset();
        COMPILED_COMPUTE_NANOS.reset();
        FILL_ARRAY_CALLS.reset();
        FILL_ARRAY_SAMPLES.reset();
        FILL_ARRAY_NANOS.reset();
        FILL_CELL_CALLS.reset();
        FILL_CELL_SAMPLES.reset();
        FILL_CELL_NANOS.reset();
        ACCUMULATE_CELL_CALLS.reset();
        ACCUMULATE_CELL_SAMPLES.reset();
        ACCUMULATE_CELL_NANOS.reset();
        EXTERN_INVOKE_CALLS.reset();
        EXTERN_INVOKE_SAMPLES.reset();
        EXTERN_INVOKE_NANOS.reset();
        MARKER_INVOKE_CALLS.reset();
        MARKER_INVOKE_SAMPLES.reset();
        MARKER_INVOKE_NANOS.reset();
        CACHE_FAST_PATH_CALLS.reset();
        CACHE_FAST_PATH_SAMPLES.reset();
        CACHE_FAST_PATH_NANOS.reset();
        COMPILE_ROOTS.reset();
        COMPILE_FAILURES.reset();
        COMPILE_SAMPLES.reset();
        COMPILE_NANOS.reset();
        COMPILE_QUEUE_WAITS.reset();
        COMPILE_QUEUE_WAIT_NANOS.reset();
        EXTERN_CLASSES.clear();
        MARKER_CLASSES.clear();
        GENERATED_CLASSES.clear();
        FALLBACK_CLASSES.clear();
        SAMPLE_STATE.get().sequence = 0L;
    }

    public static String summary() {
        Stats s = snapshot();
        return "DFC telemetry: enabled=" + s.enabled()
                + ", compiledCompute=" + s.compiledComputeCalls()
                + ", fillArray=" + s.fillArrayCalls()
                + ", fillCell=" + s.fillCellCalls()
                + ", extern=" + s.externInvokeCalls()
                + ", marker=" + s.markerInvokeCalls()
                + ", cacheFastPath=" + s.cacheFastPathCalls()
                + ", compileRoots=" + s.compileRoots()
                + ", compileMs=" + millis(s.compileNanos())
                + ", externEstMs=" + millis(s.estimatedExternInvokeNanos())
                + ", markerEstMs=" + millis(s.estimatedMarkerInvokeNanos());
    }

    private static String millis(double nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static long elapsedSince(long startedAt) {
        return startedAt == 0L ? 0L : System.nanoTime() - startedAt;
    }

    private static void recordHotCall(LongAdder calls, long elapsedNanos) {
        if (PRECISE_HOT_COUNTERS || elapsedNanos == 0L) {
            calls.increment();
        } else {
            calls.add(SAMPLE_SCALE);
        }
    }

    private static void recordClass(ConcurrentHashMap<String, ClassCounter> map, String className,
                                    long elapsedNanos, boolean exact) {
        if (!exact && elapsedNanos == 0L && !PRECISE_HOT_COUNTERS) {
            return;
        }
        ClassCounter counter = trackedCounter(map, className);
        if (counter == null) {
            return;
        }
        if (exact || PRECISE_HOT_COUNTERS || elapsedNanos == 0L) {
            counter.calls.increment();
        } else {
            counter.calls.add(SAMPLE_SCALE);
        }
        if (elapsedNanos != 0L) {
            counter.sampledNanos.add(elapsedNanos);
        }
    }

    private static ClassCounter trackedCounter(ConcurrentHashMap<String, ClassCounter> map, String className) {
        ClassCounter existing = map.get(className);
        if (existing != null) {
            return existing;
        }
        synchronized (map) {
            existing = map.get(className);
            if (existing != null) {
                return existing;
            }
            if (map.size() >= MAX_TRACKED_CLASSES) {
                return null;
            }
            ClassCounter created = new ClassCounter();
            map.put(className, created);
            return created;
        }
    }

    private static List<ClassStats> snapshotClasses(ConcurrentHashMap<String, ClassCounter> map) {
        List<ClassStats> out = new ArrayList<>(map.size());
        map.forEach((name, counter) -> out.add(new ClassStats(name, counter.calls.sum(), counter.sampledNanos.sum())));
        out.sort(Comparator.comparingLong(ClassStats::calls).reversed().thenComparing(ClassStats::className));
        if (out.size() > 12) {
            return new ArrayList<>(out.subList(0, 12));
        }
        return out;
    }

    private static final class ClassCounter {
        private final LongAdder calls = new LongAdder();
        private final LongAdder sampledNanos = new LongAdder();
    }

    private static final class SampleState {
        private long sequence;
    }
}
