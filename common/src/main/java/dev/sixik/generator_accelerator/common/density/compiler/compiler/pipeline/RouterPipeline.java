package dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuEligibility;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadParity;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.sixik.ga_utils.javatogpu.api.observability.GpuPreparedInvocationTimings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * Top-level orchestration for replacing every {@link NoiseRouter} field with a compiled
 * version. Holds aggregate stats consumed by the {@code /dfc} command.
 *
 * <p><strong>"Roots compiled" in {@code /dfc stats}</strong> is incremented once for every
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler#compile} that
 * finishes (global class-cache hit or miss). A full {@link RandomState} run therefore does
 * {@code 15} (router fields) + {@code 6} (climate sampler fields, if enabled in config) ~=
 * <strong>21</strong> roots <em>per</em> {@code RandomState} construction. With many mods,
 * pregen, multi-world, or parallel noise configs you can have dozens of {@code RandomState}
 * instances, so 800-1000+ roots on one session is often {@code 21 * ~40-50} and not a counter
 * bug. The same is true in <strong>vanilla + DFC</strong> alone: a long session, menu,
 * client+server, or many dimension transitions can build many {@code RandomState} instances
 * and cumulatively high root counts. Compare {@code global class cache: hits} vs
 * {@code codegen misses}; large time is usually from misses, not from hits re-linking a
 * cached class.
 *
 * <p><strong>"Unique nodes" in {@code /dfc stats}:</strong> a running total of
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRBuilder#internedCount()}
 * (distinct IR node identities) summed across <em>every</em> root compile, not a single-DF
 * live graph size. To compare why two field graphs share a {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.CompilationFingerprint},
 * inspect the IR + constant pool. Router fields are compiled eagerly during
 * {@code RandomState} construction.
 */
public final class RouterPipeline {

    private static final AtomicInteger ROOTS_COMPILED = new AtomicInteger();
    private static final AtomicLong UNIQUE_NODES_TOTAL = new AtomicLong();
    private static final AtomicLong CSE_SAVINGS_TOTAL = new AtomicLong();
    private static final AtomicLong HELPERS_TOTAL = new AtomicLong();
    /** Cumulative count of fixpoint iterations across all compiled roots that
     *  produced at least one peephole rewrite. A near-zero value here means the
     *  routers are already canonical and the {@link
     *  dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IROptimizer}
     *  pass is mostly an identity walk; a high value suggests the upstream JSON
     *  is leaving a lot of foldable structure on the table. */
    private static final AtomicLong OPT_REWRITES_TOTAL = new AtomicLong();
    /** Cumulative count of {@code NormalNoise} instances that were specialised
     *  away by the Tier-3 noise inlining pass. Each specialisation replaces one
     *  {@code INVOKEVIRTUAL NormalNoise.getValue} call site with a fully unrolled
     *  per-octave loop, so this number directly correlates with the eliminated
     *  megamorphic call-site count in the steady-state evaluator. */
    private static final AtomicLong NOISES_INLINED_TOTAL = new AtomicLong();
    private static final AtomicLong BLENDED_NOISES_INLINED_TOTAL = new AtomicLong();
    private static final AtomicLong BLENDED_OCTAVES_EMITTED_TOTAL = new AtomicLong();
    /** Cumulative count of individual {@code ImprovedNoise} octaves whose
     *  contribution was unrolled inline. Tracks the actual size win: a single
     *  noise with 8 active octaves contributes 8 here, while a 1-octave noise
     *  contributes 1, even though both bump {@link #NOISES_INLINED_TOTAL} the
     *  same amount. */
    private static final AtomicLong OCTAVES_INLINED_TOTAL = new AtomicLong();
    private static final AtomicLong GLOBAL_CLASS_CACHE_HITS = new AtomicLong();
    private static final AtomicLong GLOBAL_CODEGEN_MISSES = new AtomicLong();
    private static final AtomicLong GPU_ELIGIBLE_ROOTS = new AtomicLong();
    private static final AtomicLong GPU_BLOCKED_ROOTS = new AtomicLong();
    private static final AtomicLong GPU_BLOCKERS_TOTAL = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_READY_ROOTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BLOCKED_ROOTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_NODES_TOTAL = new AtomicLong();
    private static final ConcurrentHashMap<String, LongAdder> GPU_BLOCKER_COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> GPU_PAYLOAD_UNSUPPORTED_COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> GPU_PAYLOAD_BATCH_RUNTIME_GATE_COUNTS = new ConcurrentHashMap<>();
    private static final AtomicLong GPU_PAYLOAD_PARITY_CHECKS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_PARITY_PASSES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_PARITY_FAILURES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_PARITY_POINTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_PARITY_MAX_ABS_ERROR_BITS =
            new AtomicLong(Double.doubleToRawLongBits(0.0D));
    private static final AtomicReference<String> GPU_PAYLOAD_PARITY_FIRST_FAILURE = new AtomicReference<>("none");
    private static final AtomicLong GPU_PAYLOAD_BATCH_ATTEMPTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_GPU_SUCCESSES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_CPU_FALLBACKS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_POINTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_GPU_SUCCESS_POINTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_CPU_FALLBACK_POINTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_EXTERN_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_INVOKE_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_COLD_INVOKES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_COLD_INVOKE_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_WARM_INVOKES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_WARM_INVOKE_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_LOCK_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_LOCK_HELD_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_LOCK_ENTRIES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_LOCK_BUSY_SKIPS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_MICRO_LAUNCHES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_MICRO_REQUESTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_MICRO_SLOTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_MICRO_SINGLES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_MICRO_SKIPPED_LAUNCHES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_MICRO_SKIPPED_REQUESTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_TRIGGERS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_SKIPS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_BATCHES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_CACHE_HITS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_CACHE_MISSES = new AtomicLong();
    private static final AtomicReference<String> GPU_PAYLOAD_BATCH_STATIC_ARGS = new AtomicReference<>("none");
    private static final AtomicReference<String> GPU_PAYLOAD_BATCH_DYNAMIC_ARGS = new AtomicReference<>("none");
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_TIMING_TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_COUNT = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_COUNT = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_COUNT = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_BYTES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_COUNT = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_BYTES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_BIND_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_BIND_COUNT = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_SUBMIT_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_QUEUE_FINISH_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_READBACK_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_READBACK_COUNT = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PREPARED_READBACK_BYTES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_PARITY_NANOS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_TOTAL_NANOS = new AtomicLong();
    private static final AtomicReference<String> GPU_PAYLOAD_BATCH_FIRST_FALLBACK = new AtomicReference<>("none");
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_PARITY_CHECKS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_PARITY_PASSES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FAILURES = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_PARITY_POINTS = new AtomicLong();
    private static final AtomicLong GPU_PAYLOAD_BATCH_RUNTIME_PARITY_MAX_ABS_ERROR_BITS =
            new AtomicLong(Double.doubleToRawLongBits(0.0D));
    private static final AtomicReference<String> GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FIRST_FAILURE =
            new AtomicReference<>("none");

    /**
     * Number of compiled roots (cache miss <em>or</em> cache hit) whose class
     * carries a {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.CellLatticeOption}
     * fast-path ({@code lattice_y} + {@code lattice_inner} + {@code fillArray}
     * override). Counted per-root so {@code rootsCompiled - latticePlansEmitted}
     * is the number of routers that fell back to the scalar
     * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction#fillArray}
     * path either because the analysis found nothing worth hoisting or because
     * the planner is gated off by {@code -Ddfc.cell_lattice=false}.
     */
    private static final AtomicLong LATTICE_PLANS_EMITTED = new AtomicLong();
    /**
     * Roots where the lattice planner ran and returned no plan (or was disabled).
     * Surfaces "the scalar fallback fired" without confusing it with "compilation
     * failed entirely" (the latter falls back to the original DensityFunction and
     * never hits this counter).
     */
    private static final AtomicLong LATTICE_FALLBACKS = new AtomicLong();
    private static final AtomicLong CELL_ADD_LATTICE_SPECIALIZED_ROOTS = new AtomicLong();
    private static final AtomicLong CELL_ADD_BEARDIFIER_SPECIALIZED_ROOTS = new AtomicLong();
    private static final AtomicLong CELL_ADD_EXTERN_SPECIALIZED_ROOTS = new AtomicLong();
    private static final AtomicLong CELL_SCALAR_MARKER_SPECIALIZED_ROOTS = new AtomicLong();

    /**
     * Compiles each router / sampler top-level field in the GA compile lane. Running
     * inside that ForkJoinPool keeps {@code IntStream.parallel} off the common pool.
     */
    private RouterPipeline() {}

    private static void compileFieldsParallel(CompilingVisitor visitor, DensityFunction[] sources,
            DensityFunction[] compiled, int n, String failureKind) {
        compileFieldsParallel(visitor, null, sources, compiled, n, failureKind);
    }

    private static void compileFieldsParallel(CompilingVisitor visitor, String[] names, DensityFunction[] sources,
            DensityFunction[] compiled, int n, String failureKind) {
        RootSelection selection = names == null ? RootSelection.all() : RootSelection.parse(RandomStateCompileBudget.routerRoots());
        try {
            IntStream.range(0, n).parallel().forEach(i -> {
                DensityFunction src = sources[i];
                if (!selection.includes(names, i)) {
                    compiled[i] = src;
                    return;
                }
                try {
                    compiled[i] = visitor.apply(src);
                } catch (Throwable t) {
                    DensityFunctionCompiler.LOGGER.debug(
                            "RouterPipeline.{} failed for field {} (will retry on next access); "
                                    + "this is normal when registries are not yet bound.",
                            failureKind, i, t);
                    compiled[i] = src;
                }
            });
        } catch (Exception e) {
            DensityFunctionCompiler.LOGGER.debug(
                    "RouterPipeline: parallel " + failureKind + " failed; using sequential", e.getCause());
            compileFieldsSequential(visitor, names, sources, compiled, n, failureKind, selection);
        }
    }

    private static void compileFieldsSequential(CompilingVisitor visitor, DensityFunction[] sources,
            DensityFunction[] compiled, int n, String failureKind) {
        compileFieldsSequential(visitor, null, sources, compiled, n, failureKind, RootSelection.all());
    }

    private static void compileFieldsSequential(CompilingVisitor visitor, String[] names, DensityFunction[] sources,
            DensityFunction[] compiled, int n, String failureKind, RootSelection selection) {
        for (int i = 0; i < n; i++) {
            DensityFunction src = sources[i];
            if (!selection.includes(names, i)) {
                compiled[i] = src;
                continue;
            }
            try {
                compiled[i] = visitor.apply(src);
            } catch (Throwable t) {
                DensityFunctionCompiler.LOGGER.debug(
                        "RouterPipeline.{} failed for field {} (will retry on next access); "
                                + "this is normal when registries are not yet bound.",
                        failureKind, i, t);
                compiled[i] = src;
            }
        }
    }

    /**
     * Compile each of the {@link NoiseRouter}'s 15 root fields independently and return a
     * fresh router pointing at the compiled equivalents. Returns the {@code original}
     * instance unchanged if every field failed (e.g. an unbound
     * {@link net.minecraft.core.Holder.Reference Holder.Reference} when called too early
     * in startup), partial success still ships a fresh router with the compiled fields.
     *
     * <p>This deliberately does <strong>not</strong> use {@link NoiseRouter#mapAll}.
     * mapAll is post-order over the entire tree, which would invoke
     * {@link CompilingVisitor#apply} on <em>every internal node</em>; each invocation
     * compiles the node into its own {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction}
     * that just delegates to its already-compiled child via INVOKEINTERFACE. With a
     * realistic Overworld router that means tens of thousands of hidden classes for the
     * 35 source roots — each layer adds metaspace pressure and a virtual call to the
     * worldgen hot path. By compiling each top-level field as a single unit we get one
     * generated class per field plus one per inner Marker subtree (which we still have
     * to keep separate so {@code NoiseChunk} can swap them with cell caches).
     *
     * <p>Failures are intentionally only logged at {@code DEBUG}: the lazy accessor
     * mixin will retry on the next read, so a transient failure isn't worth a stack
     * trace in the user's console.
     */
    public static NoiseRouter compile(NoiseRouter original) {
        String[] names = new String[]{
                "barrierNoise",
                "fluidLevelFloodednessNoise",
                "fluidLevelSpreadNoise",
                "lavaNoise",
                "temperature",
                "vegetation",
                "continents",
                "erosion",
                "depth",
                "ridges",
                "initialDensityWithoutJaggedness",
                "finalDensity",
                "veinToggle",
                "veinRidged",
                "veinGap",
        };
        DensityFunction[] sources = new DensityFunction[]{
                original.barrierNoise(),
                original.fluidLevelFloodednessNoise(),
                original.fluidLevelSpreadNoise(),
                original.lavaNoise(),
                original.temperature(),
                original.vegetation(),
                original.continents(),
                original.erosion(),
                original.depth(),
                original.ridges(),
                original.initialDensityWithoutJaggedness(),
                original.finalDensity(),
                original.veinToggle(),
                original.veinRidged(),
                original.veinGap(),
        };
        CompilingVisitor visitor = CompilingVisitor.global();
        DensityFunction[] compiled = new DensityFunction[15];
        compileFieldsParallel(visitor, names, sources, compiled, sources.length, "compile");
        boolean anyChanged = false;
        for (int i = 0; i < sources.length; i++) {
            if (compiled[i] != sources[i]) {
                anyChanged = true;
                break;
            }
        }
        if (!anyChanged) {
            return original;
        }
        return new NoiseRouter(
                compiled[0],  compiled[1],  compiled[2],  compiled[3],  compiled[4],
                compiled[5],  compiled[6],  compiled[7],  compiled[8],  compiled[9],
                compiled[10], compiled[11], compiled[12], compiled[13], compiled[14]
        );
    }

    /**
     * Compile the six climate density functions of a {@link Climate.Sampler}.
     *
     * <p>The biome system reads {@code temperature / humidity / continentalness / erosion
     * / depth / weirdness} via {@link Climate.Sampler#sample(int, int, int)} for every
     * biome lookup; that's hundreds of thousands of single-point evaluations per chunk
     * during initial generation. Each of those fields is, structurally, the same kind of
     * shifted-noise + spline tree as the corresponding {@link NoiseRouter} field, so it
     * benefits from the same JIT compilation.
     *
     * <p>The sampler must be compiled <em>after</em> {@code RandomState.<init>} has wired
     * the source DensityFunctions through {@code NoiseWiringHelper}. Compiling earlier
     * leaves every {@link DensityFunction.NoiseHolder} unbound. The IR builder keeps those
     * nodes opaque now, but compiling after wiring is still required for noise-specialized
     * bytecode and to avoid caching a pre-wiring shape.
     *
     * <p>Failures fall back to the original sampler field, identical to {@link #compile}.
     */
    public static Climate.Sampler compileSampler(Climate.Sampler original) {
        CompilingVisitor visitor = CompilingVisitor.global();
        DensityFunction[] sources = new DensityFunction[]{
                original.temperature(),
                original.humidity(),
                original.continentalness(),
                original.erosion(),
                original.depth(),
                original.weirdness(),
        };
        DensityFunction[] compiled = new DensityFunction[6];
        compileFieldsParallel(visitor, sources, compiled, sources.length, "compileSampler");
        boolean anyChanged = false;
        for (int i = 0; i < sources.length; i++) {
            if (compiled[i] != sources[i]) {
                anyChanged = true;
                break;
            }
        }
        if (!anyChanged) {
            return original;
        }
        return new Climate.Sampler(
                compiled[0], compiled[1], compiled[2],
                compiled[3], compiled[4], compiled[5],
                original.spawnTarget());
    }

    public static DebugCompileProbeResult compileDebugRoot(NoiseRouter router, String rootName) {
        if (router == null) {
            return DebugCompileProbeResult.failed(rootName, "router is null", 0L, "none");
        }
        String normalizedRootName = rootName == null || rootName.isBlank() ? "finalDensity" : rootName;
        DensityFunction source;
        try {
            source = debugRoot(router, normalizedRootName);
        } catch (IllegalArgumentException exception) {
            return DebugCompileProbeResult.failed(normalizedRootName, exception.getMessage(), 0L, "none");
        }

        long start = System.nanoTime();
        try {
            Compiler.Result result = Compiler.compileWithDetail(source);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (result == null) {
                return DebugCompileProbeResult.failed(
                        normalizedRootName,
                        "compiler returned null",
                        elapsedMs,
                        source.getClass().getName());
            }
            GpuPayloadCompiler.Result gpuPayload = result.gpuPayload();
            GpuEligibility.Report gpuEligibility = result.gpuEligibility();
            GpuPayloadParity.Report parity = result.gpuPayloadParity();
            return new DebugCompileProbeResult(
                    normalizedRootName,
                    true,
                    "compiled",
                    elapsedMs,
                    source.getClass().getName(),
                    result.compiled().getClass().getName(),
                    result.classInternalName(),
                    result.uniqueNodes(),
                    result.helpersEmitted(),
                    result.noisesSpecialized(),
                    result.octavesUnrolled(),
                    gpuEligibility != null && gpuEligibility.eligible(),
                    gpuEligibility == null ? 0 : gpuEligibility.blockerCount(),
                    gpuEligibility == null ? "none" : gpuEligibility.firstBlocker(),
                    gpuPayload != null && gpuPayload.supported(),
                    gpuPayload == null || gpuPayload.payload() == null ? 0 : gpuPayload.payload().nodeCount(),
                    gpuPayload == null || gpuPayload.payload() == null ? 0 : gpuPayload.payload().externInputCount(),
                    gpuPayload == null ? "none" : gpuPayload.firstUnsupportedNode(),
                    gpuPayload == null ? "none" : gpuPayload.firstUnsupportedDetail(),
                    parity != null && parity.checked(),
                    parity != null && parity.passed(),
                    parity == null ? 0 : parity.pointsChecked(),
                    parity == null ? 0.0D : parity.maxAbsError(),
                    parity == null ? "none" : parity.firstMismatch());
        } catch (Throwable throwable) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            return DebugCompileProbeResult.failed(
                    normalizedRootName,
                    throwable.toString(),
                    elapsedMs,
                    source.getClass().getName());
        }
    }

    private static DensityFunction debugRoot(NoiseRouter router, String rootName) {
        return switch (rootName) {
            case "barrierNoise" -> router.barrierNoise();
            case "fluidLevelFloodednessNoise" -> router.fluidLevelFloodednessNoise();
            case "fluidLevelSpreadNoise" -> router.fluidLevelSpreadNoise();
            case "lavaNoise" -> router.lavaNoise();
            case "temperature" -> router.temperature();
            case "vegetation" -> router.vegetation();
            case "continents" -> router.continents();
            case "erosion" -> router.erosion();
            case "depth" -> router.depth();
            case "ridges" -> router.ridges();
            case "initialDensityWithoutJaggedness" -> router.initialDensityWithoutJaggedness();
            case "finalDensity" -> router.finalDensity();
            case "veinToggle" -> router.veinToggle();
            case "veinRidged" -> router.veinRidged();
            case "veinGap" -> router.veinGap();
            default -> throw new IllegalArgumentException("unknown router root: " + rootName);
        };
    }

    public static void recordCompiledRoot(int uniqueNodes, int csePostInternSavings) {
        ROOTS_COMPILED.incrementAndGet();
        UNIQUE_NODES_TOTAL.addAndGet(uniqueNodes);
        CSE_SAVINGS_TOTAL.addAndGet(csePostInternSavings);
    }

    /**
     * A full IR build and pool ran, but a hidden class for this fingerprint
     * already existed; no new class was added to the JVM.
     */
    public static void recordRootFromGlobalClassCache(int uniqueNodes, int csePostInternSavings) {
        GLOBAL_CLASS_CACHE_HITS.incrementAndGet();
        ROOTS_COMPILED.incrementAndGet();
        UNIQUE_NODES_TOTAL.addAndGet(uniqueNodes);
        CSE_SAVINGS_TOTAL.addAndGet(csePostInternSavings);
    }

    public static void recordGlobalCacheCodegenMiss() {
        GLOBAL_CODEGEN_MISSES.incrementAndGet();
    }

    public static void recordHelpers(int helpersEmitted) {
        if (helpersEmitted > 0) HELPERS_TOTAL.addAndGet(helpersEmitted);
    }

    public static void recordOptimizerRewrites(int rewrites) {
        if (rewrites > 0) OPT_REWRITES_TOTAL.addAndGet(rewrites);
    }

    /**
     * Tier 3: record one compiled root's noise-inlining contribution. {@code noisesSpecialized}
     * counts {@code NormalNoise} instances whose {@code getValue} call was replaced by an
     * unrolled per-octave loop; {@code octavesUnrolled} sums per-instance active-octave counts.
     */
    public static void recordNoiseInline(int noisesSpecialized, int octavesUnrolled) {
        if (noisesSpecialized > 0) NOISES_INLINED_TOTAL.addAndGet(noisesSpecialized);
        if (octavesUnrolled > 0) OCTAVES_INLINED_TOTAL.addAndGet(octavesUnrolled);
    }

    /** Inlined {@link net.minecraft.world.level.levelgen.synth.BlendedNoise} roots. */
    public static void recordBlendedInline(int blendedSpecialized, long blendedNonNullOctaves) {
        if (blendedSpecialized > 0) BLENDED_NOISES_INLINED_TOTAL.addAndGet(blendedSpecialized);
        if (blendedNonNullOctaves > 0) BLENDED_OCTAVES_EMITTED_TOTAL.addAndGet(blendedNonNullOctaves);
    }

    /**
     * One per compiled root: bumps {@link #LATTICE_PLANS_EMITTED} when the
     * codegen produced a lattice fast path, otherwise bumps
     * {@link #LATTICE_FALLBACKS}. Called from {@link
     * dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler}
     * regardless of whether this is a cache hit or miss; the lattice plan is
     * baked into the cached bundle, so the same hidden class always behaves
     * the same way.
     */
    public static void recordLatticePlan(boolean emitted) {
        if (emitted) LATTICE_PLANS_EMITTED.incrementAndGet();
        else LATTICE_FALLBACKS.incrementAndGet();
    }

    public static void recordCellFillSpecializations(boolean addLatticeSpecialized,
            boolean addBeardifierSpecialized, boolean addExternSpecialized,
            boolean scalarMarkerSpecialized) {
        if (addLatticeSpecialized) CELL_ADD_LATTICE_SPECIALIZED_ROOTS.incrementAndGet();
        if (addBeardifierSpecialized) CELL_ADD_BEARDIFIER_SPECIALIZED_ROOTS.incrementAndGet();
        if (addExternSpecialized) CELL_ADD_EXTERN_SPECIALIZED_ROOTS.incrementAndGet();
        if (scalarMarkerSpecialized) CELL_SCALAR_MARKER_SPECIALIZED_ROOTS.incrementAndGet();
    }

    public static void recordGpuEligibility(boolean eligible, int blockerCount) {
        if (eligible) {
            GPU_ELIGIBLE_ROOTS.incrementAndGet();
        } else {
            GPU_BLOCKED_ROOTS.incrementAndGet();
            if (blockerCount > 0) {
                GPU_BLOCKERS_TOTAL.addAndGet(blockerCount);
            }
        }
    }

    public static void recordGpuEligibility(GpuEligibility.Report report) {
        if (report == null) {
            return;
        }
        recordGpuEligibility(report.eligible(), report.blockerCount());
        for (var entry : report.blockers().entrySet()) {
            int count = entry.getValue();
            if (count > 0) {
                addCount(GPU_BLOCKER_COUNTS, entry.getKey().name(), count);
            }
        }
    }

    public static void recordGpuPayload(boolean ready, int nodeCount) {
        if (ready) {
            GPU_PAYLOAD_READY_ROOTS.incrementAndGet();
            if (nodeCount > 0) {
                GPU_PAYLOAD_NODES_TOTAL.addAndGet(nodeCount);
            }
        } else {
            GPU_PAYLOAD_BLOCKED_ROOTS.incrementAndGet();
        }
    }

    public static void recordGpuPayload(GpuPayloadCompiler.Result result) {
        if (result == null) {
            recordGpuPayload(false, 0);
            addCount(GPU_PAYLOAD_UNSUPPORTED_COUNTS, "null-result", 1);
            return;
        }
        recordGpuPayload(result.supported(), result.supported() && result.payload() != null
                ? result.payload().nodeCount()
                : 0);
        if (!result.supported()) {
            addCount(GPU_PAYLOAD_UNSUPPORTED_COUNTS, result.firstUnsupportedNode(), 1);
        }
    }

    private static void addCount(ConcurrentHashMap<String, LongAdder> counts, String key, long amount) {
        if (key == null || key.isBlank() || amount <= 0L) {
            return;
        }
        LongAdder adder = counts.computeIfAbsent(key, ignored -> new LongAdder());
        adder.add(amount);
    }

    private static List<String> topCounts(ConcurrentHashMap<String, LongAdder> counts, int limit) {
        ArrayList<String> out = new ArrayList<>();
        counts.entrySet().stream()
                .map(entry -> new CountEntry(entry.getKey(), entry.getValue().sum()))
                .filter(entry -> entry.count() > 0L)
                .sorted(Comparator.comparingLong(CountEntry::count).reversed()
                        .thenComparing(CountEntry::key))
                .limit(limit)
                .forEach(entry -> out.add(entry.key() + "=" + entry.count()));
        return out;
    }

    public static void recordGpuPayloadParity(GpuPayloadParity.Report report) {
        if (report == null || !report.checked()) {
            return;
        }
        GPU_PAYLOAD_PARITY_CHECKS.incrementAndGet();
        GPU_PAYLOAD_PARITY_POINTS.addAndGet(report.pointsChecked());
        updateGpuPayloadParityMaxAbsError(report.maxAbsError());
        if (report.passed()) {
            GPU_PAYLOAD_PARITY_PASSES.incrementAndGet();
        } else {
            GPU_PAYLOAD_PARITY_FAILURES.incrementAndGet();
            if (report.firstMismatch() != null && !report.firstMismatch().isBlank()) {
                GPU_PAYLOAD_PARITY_FIRST_FAILURE.compareAndSet("none", report.firstMismatch());
            }
        }
    }

    public static void recordGpuPayloadBatchAttempt(int points) {
        GPU_PAYLOAD_BATCH_ATTEMPTS.incrementAndGet();
        if (points > 0) {
            GPU_PAYLOAD_BATCH_POINTS.addAndGet(points);
        }
    }

    public static void recordGpuPayloadBatchTimings(
            long externNanos,
            long invokeNanos,
            long parityNanos,
            long totalNanos) {
        recordGpuPayloadBatchTimings(externNanos, invokeNanos, parityNanos, totalNanos, false, false);
    }

    public static void recordGpuPayloadBatchTimings(
            long externNanos,
            long invokeNanos,
            long parityNanos,
            long totalNanos,
            boolean preparedLauncherCacheHit) {
        recordGpuPayloadBatchTimings(externNanos, invokeNanos, parityNanos, totalNanos, preparedLauncherCacheHit, true);
    }

    public static void recordGpuPayloadBatchTimings(
            long externNanos,
            long invokeNanos,
            long parityNanos,
            long totalNanos,
            boolean preparedLauncherCacheHit,
            boolean preparedLauncherInvoked) {
        if (externNanos > 0L) {
            GPU_PAYLOAD_BATCH_EXTERN_NANOS.addAndGet(externNanos);
        }
        if (invokeNanos > 0L) {
            GPU_PAYLOAD_BATCH_INVOKE_NANOS.addAndGet(invokeNanos);
            if (!preparedLauncherInvoked) {
                // Busy opportunistic fallbacks still contribute caller wall time,
                // but they did not touch the prepared launcher cache.
            } else if (preparedLauncherCacheHit) {
                GPU_PAYLOAD_BATCH_WARM_INVOKES.incrementAndGet();
                GPU_PAYLOAD_BATCH_WARM_INVOKE_NANOS.addAndGet(invokeNanos);
                GPU_PAYLOAD_BATCH_PREPARED_CACHE_HITS.incrementAndGet();
            } else {
                GPU_PAYLOAD_BATCH_COLD_INVOKES.incrementAndGet();
                GPU_PAYLOAD_BATCH_COLD_INVOKE_NANOS.addAndGet(invokeNanos);
                GPU_PAYLOAD_BATCH_PREPARED_CACHE_MISSES.incrementAndGet();
            }
        }
        if (parityNanos > 0L) {
            GPU_PAYLOAD_BATCH_PARITY_NANOS.addAndGet(parityNanos);
        }
        if (totalNanos > 0L) {
            GPU_PAYLOAD_BATCH_TOTAL_NANOS.addAndGet(totalNanos);
        }
    }

    public static void recordGpuPayloadBatchPreparedTimings(GpuPreparedInvocationTimings timings) {
        if (timings == null) {
            return;
        }
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_TIMING_TOTAL_NANOS, timings.totalNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_NANOS, timings.bufferAllocateNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_COUNT, timings.bufferAllocateCount());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_NANOS, timings.bufferReuseNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_COUNT, timings.bufferReuseCount());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_NANOS, timings.uploadNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_COUNT, timings.uploadCount());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_BYTES, timings.uploadBytes());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_COUNT, timings.skippedUploadCount());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_BYTES, timings.skippedUploadBytes());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_BIND_NANOS, timings.bindNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_BIND_COUNT, timings.bindCount());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_SUBMIT_NANOS, timings.enqueueSubmitNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_WAIT_NANOS, timings.enqueueWaitNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_QUEUE_FINISH_NANOS, timings.queueFinishNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_READBACK_NANOS, timings.readbackNanos());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_READBACK_COUNT, timings.readbackCount());
        addPositive(GPU_PAYLOAD_BATCH_PREPARED_READBACK_BYTES, timings.readbackBytes());
    }

    public static void recordGpuPayloadBatchRuntimeLock(long waitNanos, long heldNanos) {
        GPU_PAYLOAD_BATCH_RUNTIME_LOCK_ENTRIES.incrementAndGet();
        addPositive(GPU_PAYLOAD_BATCH_RUNTIME_LOCK_WAIT_NANOS, waitNanos);
        addPositive(GPU_PAYLOAD_BATCH_RUNTIME_LOCK_HELD_NANOS, heldNanos);
        if (heldNanos <= 0L) {
            GPU_PAYLOAD_BATCH_RUNTIME_LOCK_BUSY_SKIPS.incrementAndGet();
        }
    }

    public static void recordGpuPayloadBatchMicroBatch(int requests, int slots) {
        if (requests <= 0 || slots <= 0) {
            return;
        }
        GPU_PAYLOAD_BATCH_MICRO_LAUNCHES.incrementAndGet();
        GPU_PAYLOAD_BATCH_MICRO_REQUESTS.addAndGet(requests);
        GPU_PAYLOAD_BATCH_MICRO_SLOTS.addAndGet(slots);
        if (requests == 1) {
            GPU_PAYLOAD_BATCH_MICRO_SINGLES.incrementAndGet();
        }
    }

    public static void recordGpuPayloadBatchMicroBatchSkipped(int requests) {
        if (requests <= 0) {
            return;
        }
        GPU_PAYLOAD_BATCH_MICRO_SKIPPED_LAUNCHES.incrementAndGet();
        GPU_PAYLOAD_BATCH_MICRO_SKIPPED_REQUESTS.addAndGet(requests);
    }

    public static void recordGpuPayloadBatchRuntimeBackoffTrigger(int streak, int backoffBatches) {
        GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_TRIGGERS.incrementAndGet();
        addPositive(GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_BATCHES, backoffBatches);
    }

    public static void recordGpuPayloadBatchRuntimeBackoffSkip() {
        GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_SKIPS.incrementAndGet();
    }

    private static void addPositive(AtomicLong target, long value) {
        if (value > 0L) {
            target.addAndGet(value);
        }
    }

    public static void recordGpuPayloadBatchGpuSuccess(int points) {
        GPU_PAYLOAD_BATCH_GPU_SUCCESSES.incrementAndGet();
        if (points > 0) {
            GPU_PAYLOAD_BATCH_GPU_SUCCESS_POINTS.addAndGet(points);
        }
    }

    public static void recordGpuPayloadBatchArgumentLayout(String staticArgs, String dynamicArgs) {
        if (staticArgs != null && !staticArgs.isBlank()) {
            GPU_PAYLOAD_BATCH_STATIC_ARGS.set(staticArgs);
        }
        if (dynamicArgs != null && !dynamicArgs.isBlank()) {
            GPU_PAYLOAD_BATCH_DYNAMIC_ARGS.set(dynamicArgs);
        }
    }

    public static void recordGpuPayloadBatchRuntimeGate(String gate) {
        addCount(GPU_PAYLOAD_BATCH_RUNTIME_GATE_COUNTS, gate, 1L);
    }

    public static void recordGpuPayloadBatchCpuFallback(int points, String reason) {
        GPU_PAYLOAD_BATCH_CPU_FALLBACKS.incrementAndGet();
        if (points > 0) {
            GPU_PAYLOAD_BATCH_CPU_FALLBACK_POINTS.addAndGet(points);
        }
        if (reason != null && !reason.isBlank()) {
            GPU_PAYLOAD_BATCH_FIRST_FALLBACK.compareAndSet("none", reason);
        }
    }

    public static void recordGpuPayloadBatchRuntimeParity(
            boolean checked,
            boolean passed,
            int pointsChecked,
            double maxAbsError,
            String failureReason) {
        if (!checked) {
            return;
        }
        GPU_PAYLOAD_BATCH_RUNTIME_PARITY_CHECKS.incrementAndGet();
        if (pointsChecked > 0) {
            GPU_PAYLOAD_BATCH_RUNTIME_PARITY_POINTS.addAndGet(pointsChecked);
        }
        updateMaxAbsError(GPU_PAYLOAD_BATCH_RUNTIME_PARITY_MAX_ABS_ERROR_BITS, maxAbsError);
        if (passed) {
            GPU_PAYLOAD_BATCH_RUNTIME_PARITY_PASSES.incrementAndGet();
        } else {
            GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FAILURES.incrementAndGet();
            if (failureReason != null && !failureReason.isBlank()) {
                GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FIRST_FAILURE.compareAndSet("none", failureReason);
            }
        }
    }

    private static void updateGpuPayloadParityMaxAbsError(double value) {
        if (value < 0.0D) {
            return;
        }
        updateMaxAbsError(GPU_PAYLOAD_PARITY_MAX_ABS_ERROR_BITS, value);
    }

    private static void updateMaxAbsError(AtomicLong target, double value) {
        if (value < 0.0D) {
            return;
        }
        while (true) {
            long prevBits = target.get();
            double prev = Double.longBitsToDouble(prevBits);
            if (value <= prev) {
                return;
            }
            if (target.compareAndSet(prevBits, Double.doubleToRawLongBits(value))) {
                return;
            }
        }
    }

    public record Stats(int rootsCompiled, int globalClassCacheSize, long uniqueNodes,
                         long savedByCse, long helpersEmitted, long optimizerRewrites,
                         long noisesInlined, long octavesInlined,
                         long blendedInlined, long blendedOctavesEmitted,
                         long globalClassCacheHits, long globalCodegenCacheMisses,
                         /**
                          * NormalNoise instances DFC tried to inline but bailed because the
                          * NormalNoiseAccessor / PerlinNoiseAccessor mixin was not bound.
                          * A non-zero value here points at a broken vanilla refactor or a
                         * coremod stripping our class transformations. DFC stays correct
                         * (it falls back to legacy {@code INVOKEVIRTUAL NormalNoise.getValue})
                         * but loses the inline win for those samplers. Compare against
                         * {@link #noisesInlined} for the inline-rate ratio.
                          */
                         long noiseMixinFailures,
                         /** {@code BlendedNoise} instances we couldn't inline for the same reason. */
                         long blendedMixinFailures,
                         /**
                          * Sum of octaves we silently skipped during noise-spec build (null
                          * {@code ImprovedNoise} array slots, zero-amplitude entries). High
                         * counts here are normal; many vanilla noises have about half their octave
                         * slots intentionally null, but the number is exposed so a sudden jump
                         * after a Mojang refactor surfaces immediately.
                          */
                         long octavesSkipped,
                         /** Total bytes we estimate the global class cache saved by reusing a
                          *  fingerprinted hidden class instead of regenerating its bytecode.
                          *  Surfaced in {@code /dfc stats}. */
                         long globalClassCacheBytesSaved,
                         /** Number of {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction}
                          *  instances backed by a shared, cached hidden class (vs. its own
                          *  freshly emitted one). */
                         long globalClassCacheInstancesShared,
                         /** Shape-cache hits where runtime-binding identities differed from the defining compile. */
                         long globalClassCacheShapeHitsAcrossExactMisses,
                         /** Compiled roots whose codegen produced a {@link
                          * dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.CellLatticeOption}
                          * fast path (lattice_y + lattice_inner + fillArray override). */
                         long latticePlansEmitted,
                         /** Compiled roots that fell back to the scalar fillArray
                          * path because the planner found no axis-only hoist worth
                          * promoting (or {@code -Ddfc.cell_lattice=false}). */
                         long latticeFallbacks,
                          /** Compiled roots whose cell filler specialized ADD(scalar residual, lattice). */
                          long cellAddLatticeSpecializedRoots,
                          /** Compiled roots whose cell filler specialized ADD(residual, Beardifier). */
                          long cellAddBeardifierSpecializedRoots,
                          /** Compiled roots whose cell filler specialized ADD(extern cell-fill, residual). */
                          long cellAddExternSpecializedRoots,
                          /** Compiled roots whose cell filler specialized a small marker-only scalar expression. */
                          long cellScalarMarkerSpecializedRoots,
                         /** Roots whose current IR has no JavaToGpu-readiness blockers. */
                         long gpuEligibleRoots,
                          /** Roots currently blocked from a JavaToGpu-style kernel. */
                          long gpuBlockedRoots,
                          /** Total conservative GPU blockers observed across blocked roots. */
                          long gpuBlockersTotal,
                          /** Roots packed into the primitive GPU payload subset. */
                          long gpuPayloadReadyRoots,
                          /** Roots not yet packable into the primitive GPU payload subset. */
                          long gpuPayloadBlockedRoots,
                          /** Primitive payload nodes emitted across ready roots. */
                          long gpuPayloadNodesTotal,
                          /** Diagnostic CPU-DFC vs GPU-payload mirror checks. */
                          long gpuPayloadParityChecks,
                          /** Payload mirror checks that matched CPU DFC. */
                          long gpuPayloadParityPasses,
                          /** Payload mirror checks that found a mismatch. */
                          long gpuPayloadParityFailures,
                          /** Coordinate points compared by payload parity. */
                          long gpuPayloadParityPoints,
                          /** Largest absolute delta observed by payload parity. */
                          double gpuPayloadParityMaxAbsError,
                          /** First payload parity mismatch, if any. */
                          String gpuPayloadParityFirstFailure,
                          /** Top conservative GPU blocker categories. */
                          List<String> gpuBlockerCounts,
                          /** Top first unsupported nodes from primitive payload packing. */
                          List<String> gpuPayloadUnsupportedCounts,
                          /** Runtime GPU gate categories observed before JavaToGpu launch attempts. */
                          List<String> gpuPayloadBatchRuntimeGateCounts,
                          /** Real DFC cell batches routed into the JavaToGpu payload path. */
                          long gpuPayloadBatchAttempts,
                          /** Routed DFC cell batches completed on GPU. */
                          long gpuPayloadBatchGpuSuccesses,
                          /** Routed DFC cell batches that fell back to CPU DFC. */
                          long gpuPayloadBatchCpuFallbacks,
                          /** Total cell points attempted by the GPU batch path. */
                          long gpuPayloadBatchPoints,
                          /** Total cell points actually completed on GPU. */
                          long gpuPayloadBatchGpuSuccessPoints,
                          /** Total cell points routed back to CPU from the GPU batch path. */
                          long gpuPayloadBatchCpuFallbackPoints,
                          /** Time spent computing extern input values before GPU launch. */
                          long gpuPayloadBatchExternNanos,
                          /** Time spent inside JavaToGpu invocation. */
                          long gpuPayloadBatchInvokeNanos,
                          /** Real GPU batch invokes that built or rebuilt a prepared launcher. */
                          long gpuPayloadBatchColdInvokes,
                          /** Time spent in cold real GPU batch invokes. */
                          long gpuPayloadBatchColdInvokeNanos,
                          /** Real GPU batch invokes that reused the prepared launcher. */
                          long gpuPayloadBatchWarmInvokes,
                          /** Time spent in warm real GPU batch invokes. */
                          long gpuPayloadBatchWarmInvokeNanos,
                          /** Cumulative time waiting for the serialized GPU runtime lock. */
                          long gpuPayloadBatchRuntimeLockWaitNanos,
                          /** Cumulative time spent holding the serialized GPU runtime lock. */
                          long gpuPayloadBatchRuntimeLockHeldNanos,
                          /** Number of entries into the serialized GPU runtime lock. */
                          long gpuPayloadBatchRuntimeLockEntries,
                          /** Batches that skipped GPU because the serialized runtime lock was busy. */
                          long gpuPayloadBatchRuntimeLockBusySkips,
                          /** Prepared runtime microbatch launches submitted to JavaToGpu. */
                          long gpuPayloadBatchMicroLaunches,
                          /** Original cell requests included in runtime microbatch launches. */
                          long gpuPayloadBatchMicroRequests,
                          /** Reserved request slots submitted across runtime microbatch launches. */
                          long gpuPayloadBatchMicroSlots,
                          /** Runtime microbatch launches that only carried the leader request. */
                          long gpuPayloadBatchMicroSingles,
                          /** Runtime microbatch launch candidates skipped because the collected group was too small. */
                          long gpuPayloadBatchMicroSkippedLaunches,
                          /** Original cell requests skipped with too-small runtime microbatch groups. */
                          long gpuPayloadBatchMicroSkippedRequests,
                          /** Times runtime GPU backoff was enabled after repeated unprofitable single microbatches. */
                          long gpuPayloadBatchRuntimeBackoffTriggers,
                          /** Runtime batch checks skipped by adaptive GPU backoff before touching the runtime lock. */
                          long gpuPayloadBatchRuntimeBackoffSkips,
                          /** Total runtime batch checks requested to skip across adaptive GPU backoff windows. */
                          long gpuPayloadBatchRuntimeBackoffBatches,
                          /** Prepared launcher cache hits during real GPU batches. */
                          long gpuPayloadBatchPreparedCacheHits,
                          /** Prepared launcher cache misses during real GPU batches. */
                          long gpuPayloadBatchPreparedCacheMisses,
                          /** Last real GPU batch static argument layout. */
                          String gpuPayloadBatchStaticArgs,
                          /** Last real GPU batch dynamic argument layout. */
                          String gpuPayloadBatchDynamicArgs,
                          /** Sum of JavaToGpu prepared hot-path stage total timings. */
                          long gpuPayloadBatchPreparedTimingTotalNanos,
                          long gpuPayloadBatchPreparedBufferAllocateNanos,
                          long gpuPayloadBatchPreparedBufferAllocateCount,
                          long gpuPayloadBatchPreparedBufferReuseNanos,
                          long gpuPayloadBatchPreparedBufferReuseCount,
                          long gpuPayloadBatchPreparedUploadNanos,
                          long gpuPayloadBatchPreparedUploadCount,
                          long gpuPayloadBatchPreparedUploadBytes,
                          long gpuPayloadBatchPreparedSkippedUploadCount,
                          long gpuPayloadBatchPreparedSkippedUploadBytes,
                          long gpuPayloadBatchPreparedBindNanos,
                          long gpuPayloadBatchPreparedBindCount,
                          long gpuPayloadBatchPreparedEnqueueSubmitNanos,
                          long gpuPayloadBatchPreparedEnqueueWaitNanos,
                          long gpuPayloadBatchPreparedQueueFinishNanos,
                          long gpuPayloadBatchPreparedReadbackNanos,
                          long gpuPayloadBatchPreparedReadbackCount,
                          long gpuPayloadBatchPreparedReadbackBytes,
                          /** Time spent checking runtime parity. */
                          long gpuPayloadBatchParityNanos,
                          /** Total time spent in the GPU payload fill path. */
                          long gpuPayloadBatchTotalNanos,
                          /** First GPU batch fallback reason, if any. */
                          String gpuPayloadBatchFirstFallback,
                          /** Runtime GPU-vs-CPU-mirror batch parity checks. */
                          long gpuPayloadBatchRuntimeParityChecks,
                          /** Runtime batch parity checks that passed. */
                          long gpuPayloadBatchRuntimeParityPasses,
                          /** Runtime batch parity checks that failed. */
                          long gpuPayloadBatchRuntimeParityFailures,
                          /** Runtime batch parity points compared. */
                          long gpuPayloadBatchRuntimeParityPoints,
                          /** Largest runtime batch parity absolute delta. */
                          double gpuPayloadBatchRuntimeParityMaxAbsError,
                          /** First runtime batch parity mismatch, if any. */
                          String gpuPayloadBatchRuntimeParityFirstFailure,
                          /** Lazy router wrappers allocated. */
                          long lazyWrappersCreated,
                         /** Cold lazy resolves triggered from {@code compute}. */
                         long lazyComputeResolveAttempts,
                         /** Cold lazy resolves triggered from {@code fillArray}. */
                         long lazyFillArrayResolveAttempts,
                         /** Cold lazy resolves triggered from {@code mapAll}. */
                         long lazyMapAllResolveAttempts,
                         /** Cold lazy resolves triggered from {@code minValue}. */
                         long lazyMinValueResolveAttempts,
                         /** Cold lazy resolves triggered from {@code maxValue}. */
                         long lazyMaxValueResolveAttempts,
                         /** Lazy wrappers that resolved to a compiled replacement. */
                         long lazySuccessfulCompiles,
                         /** Lazy compile attempts that threw before a fallback was available. */
                         long lazyCompileFailures,
                         /** Lazy wrappers that resolved to the original vanilla evaluator. */
                         long lazyCompileFallbacks) {
        public long lazyResolveAttempts() {
            return lazyComputeResolveAttempts + lazyFillArrayResolveAttempts
                    + lazyMapAllResolveAttempts + lazyMinValueResolveAttempts
                    + lazyMaxValueResolveAttempts;
        }
    }

    public record DebugCompileProbeResult(
            String rootName,
            boolean success,
            String reason,
            long elapsedMs,
            String sourceClass,
            String compiledClass,
            String classInternalName,
            int uniqueNodes,
            int helpersEmitted,
            int noisesSpecialized,
            int octavesUnrolled,
            boolean gpuEligible,
            int gpuBlockerCount,
            String firstGpuBlocker,
            boolean gpuPayloadSupported,
            int gpuPayloadNodes,
            int gpuPayloadExternInputs,
            String firstUnsupportedNode,
            String firstUnsupportedDetail,
            boolean parityChecked,
            boolean parityPassed,
            int parityPoints,
            double parityMaxAbsError,
            String parityFirstMismatch) {
        private static DebugCompileProbeResult failed(String rootName, String reason, long elapsedMs, String sourceClass) {
            return new DebugCompileProbeResult(
                    rootName,
                    false,
                    reason,
                    elapsedMs,
                    sourceClass,
                    "none",
                    "none",
                    0,
                    0,
                    0,
                    0,
                    false,
                    0,
                    "none",
                    false,
                    0,
                    0,
                    "none",
                    "none",
                    false,
                    false,
                    0,
                    0.0D,
                    "none");
        }
    }

    public static Stats snapshotStats() {
        OnDemandCompilingDensityFunction.LazyStats lazy =
                OnDemandCompilingDensityFunction.snapshotLazyStats();
        return new Stats(
                ROOTS_COMPILED.get(),
                GlobalCompileCache.INSTANCE.size(),
                UNIQUE_NODES_TOTAL.get(),
                CSE_SAVINGS_TOTAL.get(),
                HELPERS_TOTAL.get(),
                OPT_REWRITES_TOTAL.get(),
                NOISES_INLINED_TOTAL.get(),
                OCTAVES_INLINED_TOTAL.get(),
                BLENDED_NOISES_INLINED_TOTAL.get(),
                BLENDED_OCTAVES_EMITTED_TOTAL.get(),
                GLOBAL_CLASS_CACHE_HITS.get(),
                GLOBAL_CODEGEN_MISSES.get(),
                NoiseSpecCache.MIXIN_BIND_FAILURES.get(),
                BlendedNoiseSpecCache.MIXIN_BIND_FAILURES.get(),
                NoiseSpecCache.OCTAVES_SKIPPED.get(),
                GlobalCompileCache.INSTANCE.bytesSaved(),
                GlobalCompileCache.INSTANCE.instancesShared(),
                GlobalCompileCache.INSTANCE.shapeHitsAcrossExactMisses(),
                LATTICE_PLANS_EMITTED.get(),
                LATTICE_FALLBACKS.get(),
                CELL_ADD_LATTICE_SPECIALIZED_ROOTS.get(),
                CELL_ADD_BEARDIFIER_SPECIALIZED_ROOTS.get(),
                CELL_ADD_EXTERN_SPECIALIZED_ROOTS.get(),
                CELL_SCALAR_MARKER_SPECIALIZED_ROOTS.get(),
                GPU_ELIGIBLE_ROOTS.get(),
                GPU_BLOCKED_ROOTS.get(),
                GPU_BLOCKERS_TOTAL.get(),
                GPU_PAYLOAD_READY_ROOTS.get(),
                GPU_PAYLOAD_BLOCKED_ROOTS.get(),
                GPU_PAYLOAD_NODES_TOTAL.get(),
                GPU_PAYLOAD_PARITY_CHECKS.get(),
                GPU_PAYLOAD_PARITY_PASSES.get(),
                GPU_PAYLOAD_PARITY_FAILURES.get(),
                GPU_PAYLOAD_PARITY_POINTS.get(),
                Double.longBitsToDouble(GPU_PAYLOAD_PARITY_MAX_ABS_ERROR_BITS.get()),
                GPU_PAYLOAD_PARITY_FIRST_FAILURE.get(),
                topCounts(GPU_BLOCKER_COUNTS, 5),
                topCounts(GPU_PAYLOAD_UNSUPPORTED_COUNTS, 5),
                topCounts(GPU_PAYLOAD_BATCH_RUNTIME_GATE_COUNTS, 8),
                GPU_PAYLOAD_BATCH_ATTEMPTS.get(),
                GPU_PAYLOAD_BATCH_GPU_SUCCESSES.get(),
                GPU_PAYLOAD_BATCH_CPU_FALLBACKS.get(),
                GPU_PAYLOAD_BATCH_POINTS.get(),
                GPU_PAYLOAD_BATCH_GPU_SUCCESS_POINTS.get(),
                GPU_PAYLOAD_BATCH_CPU_FALLBACK_POINTS.get(),
                GPU_PAYLOAD_BATCH_EXTERN_NANOS.get(),
                GPU_PAYLOAD_BATCH_INVOKE_NANOS.get(),
                GPU_PAYLOAD_BATCH_COLD_INVOKES.get(),
                GPU_PAYLOAD_BATCH_COLD_INVOKE_NANOS.get(),
                GPU_PAYLOAD_BATCH_WARM_INVOKES.get(),
                GPU_PAYLOAD_BATCH_WARM_INVOKE_NANOS.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_LOCK_WAIT_NANOS.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_LOCK_HELD_NANOS.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_LOCK_ENTRIES.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_LOCK_BUSY_SKIPS.get(),
                GPU_PAYLOAD_BATCH_MICRO_LAUNCHES.get(),
                GPU_PAYLOAD_BATCH_MICRO_REQUESTS.get(),
                GPU_PAYLOAD_BATCH_MICRO_SLOTS.get(),
                GPU_PAYLOAD_BATCH_MICRO_SINGLES.get(),
                GPU_PAYLOAD_BATCH_MICRO_SKIPPED_LAUNCHES.get(),
                GPU_PAYLOAD_BATCH_MICRO_SKIPPED_REQUESTS.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_TRIGGERS.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_SKIPS.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_BATCHES.get(),
                GPU_PAYLOAD_BATCH_PREPARED_CACHE_HITS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_CACHE_MISSES.get(),
                GPU_PAYLOAD_BATCH_STATIC_ARGS.get(),
                GPU_PAYLOAD_BATCH_DYNAMIC_ARGS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_TIMING_TOTAL_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_COUNT.get(),
                GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_COUNT.get(),
                GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_COUNT.get(),
                GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_BYTES.get(),
                GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_COUNT.get(),
                GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_BYTES.get(),
                GPU_PAYLOAD_BATCH_PREPARED_BIND_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_BIND_COUNT.get(),
                GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_SUBMIT_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_WAIT_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_QUEUE_FINISH_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_READBACK_NANOS.get(),
                GPU_PAYLOAD_BATCH_PREPARED_READBACK_COUNT.get(),
                GPU_PAYLOAD_BATCH_PREPARED_READBACK_BYTES.get(),
                GPU_PAYLOAD_BATCH_PARITY_NANOS.get(),
                GPU_PAYLOAD_BATCH_TOTAL_NANOS.get(),
                GPU_PAYLOAD_BATCH_FIRST_FALLBACK.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_PARITY_CHECKS.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_PARITY_PASSES.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FAILURES.get(),
                GPU_PAYLOAD_BATCH_RUNTIME_PARITY_POINTS.get(),
                Double.longBitsToDouble(GPU_PAYLOAD_BATCH_RUNTIME_PARITY_MAX_ABS_ERROR_BITS.get()),
                GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FIRST_FAILURE.get(),
                lazy.wrappersCreated(),
                lazy.computeResolveAttempts(),
                lazy.fillArrayResolveAttempts(),
                lazy.mapAllResolveAttempts(),
                lazy.minValueResolveAttempts(),
                lazy.maxValueResolveAttempts(),
                lazy.successfulCompiles(),
                lazy.compileFailures(),
                lazy.compileFallbacks());
    }

    public static void resetStats() {
        ROOTS_COMPILED.set(0);
        UNIQUE_NODES_TOTAL.set(0L);
        CSE_SAVINGS_TOTAL.set(0L);
        HELPERS_TOTAL.set(0L);
        OPT_REWRITES_TOTAL.set(0L);
        NOISES_INLINED_TOTAL.set(0L);
        BLENDED_NOISES_INLINED_TOTAL.set(0L);
        BLENDED_OCTAVES_EMITTED_TOTAL.set(0L);
        OCTAVES_INLINED_TOTAL.set(0L);
        GLOBAL_CLASS_CACHE_HITS.set(0L);
        GLOBAL_CODEGEN_MISSES.set(0L);
        LATTICE_PLANS_EMITTED.set(0L);
        LATTICE_FALLBACKS.set(0L);
        CELL_ADD_LATTICE_SPECIALIZED_ROOTS.set(0L);
        CELL_ADD_BEARDIFIER_SPECIALIZED_ROOTS.set(0L);
        CELL_ADD_EXTERN_SPECIALIZED_ROOTS.set(0L);
        CELL_SCALAR_MARKER_SPECIALIZED_ROOTS.set(0L);
        GPU_ELIGIBLE_ROOTS.set(0L);
        GPU_BLOCKED_ROOTS.set(0L);
        GPU_BLOCKERS_TOTAL.set(0L);
        GPU_PAYLOAD_READY_ROOTS.set(0L);
        GPU_PAYLOAD_BLOCKED_ROOTS.set(0L);
        GPU_PAYLOAD_NODES_TOTAL.set(0L);
        GPU_PAYLOAD_PARITY_CHECKS.set(0L);
        GPU_PAYLOAD_PARITY_PASSES.set(0L);
        GPU_PAYLOAD_PARITY_FAILURES.set(0L);
        GPU_PAYLOAD_PARITY_POINTS.set(0L);
        GPU_PAYLOAD_PARITY_MAX_ABS_ERROR_BITS.set(Double.doubleToRawLongBits(0.0D));
        GPU_PAYLOAD_PARITY_FIRST_FAILURE.set("none");
        GPU_BLOCKER_COUNTS.clear();
        GPU_PAYLOAD_UNSUPPORTED_COUNTS.clear();
        GPU_PAYLOAD_BATCH_RUNTIME_GATE_COUNTS.clear();
        GPU_PAYLOAD_BATCH_ATTEMPTS.set(0L);
        GPU_PAYLOAD_BATCH_GPU_SUCCESSES.set(0L);
        GPU_PAYLOAD_BATCH_CPU_FALLBACKS.set(0L);
        GPU_PAYLOAD_BATCH_POINTS.set(0L);
        GPU_PAYLOAD_BATCH_GPU_SUCCESS_POINTS.set(0L);
        GPU_PAYLOAD_BATCH_CPU_FALLBACK_POINTS.set(0L);
        GPU_PAYLOAD_BATCH_EXTERN_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_INVOKE_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_COLD_INVOKES.set(0L);
        GPU_PAYLOAD_BATCH_COLD_INVOKE_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_WARM_INVOKES.set(0L);
        GPU_PAYLOAD_BATCH_WARM_INVOKE_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_LOCK_WAIT_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_LOCK_HELD_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_LOCK_ENTRIES.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_LOCK_BUSY_SKIPS.set(0L);
        GPU_PAYLOAD_BATCH_MICRO_LAUNCHES.set(0L);
        GPU_PAYLOAD_BATCH_MICRO_REQUESTS.set(0L);
        GPU_PAYLOAD_BATCH_MICRO_SLOTS.set(0L);
        GPU_PAYLOAD_BATCH_MICRO_SINGLES.set(0L);
        GPU_PAYLOAD_BATCH_MICRO_SKIPPED_LAUNCHES.set(0L);
        GPU_PAYLOAD_BATCH_MICRO_SKIPPED_REQUESTS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_TRIGGERS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_SKIPS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_BACKOFF_BATCHES.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_CACHE_HITS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_CACHE_MISSES.set(0L);
        GPU_PAYLOAD_BATCH_STATIC_ARGS.set("none");
        GPU_PAYLOAD_BATCH_DYNAMIC_ARGS.set("none");
        GPU_PAYLOAD_BATCH_PREPARED_TIMING_TOTAL_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_BUFFER_ALLOCATE_COUNT.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_BUFFER_REUSE_COUNT.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_COUNT.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_UPLOAD_BYTES.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_COUNT.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_SKIPPED_UPLOAD_BYTES.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_BIND_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_BIND_COUNT.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_SUBMIT_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_ENQUEUE_WAIT_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_QUEUE_FINISH_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_READBACK_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_READBACK_COUNT.set(0L);
        GPU_PAYLOAD_BATCH_PREPARED_READBACK_BYTES.set(0L);
        GPU_PAYLOAD_BATCH_PARITY_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_TOTAL_NANOS.set(0L);
        GPU_PAYLOAD_BATCH_FIRST_FALLBACK.set("none");
        GPU_PAYLOAD_BATCH_RUNTIME_PARITY_CHECKS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_PARITY_PASSES.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FAILURES.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_PARITY_POINTS.set(0L);
        GPU_PAYLOAD_BATCH_RUNTIME_PARITY_MAX_ABS_ERROR_BITS.set(Double.doubleToRawLongBits(0.0D));
        GPU_PAYLOAD_BATCH_RUNTIME_PARITY_FIRST_FAILURE.set("none");
        OnDemandCompilingDensityFunction.resetLazyStats();
    }

    private record CountEntry(String key, long count) {}

    private record RootSelection(boolean includeAll, Set<String> names) {
        static RootSelection all() {
            return new RootSelection(true, Set.of());
        }

        static RootSelection parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return all();
            }
            String trimmed = raw.trim();
            if ("all".equalsIgnoreCase(trimmed) || "*".equals(trimmed)) {
                return all();
            }
            if ("none".equalsIgnoreCase(trimmed)) {
                return new RootSelection(false, Set.of());
            }
            HashSet<String> selected = new HashSet<>();
            for (String part : trimmed.split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    selected.add(name.toLowerCase(Locale.ROOT));
                }
            }
            return selected.isEmpty() ? all() : new RootSelection(false, Set.copyOf(selected));
        }

        boolean includes(String[] rootNames, int index) {
            if (includeAll || rootNames == null) {
                return true;
            }
            if (index < 0 || index >= rootNames.length) {
                return false;
            }
            return names.contains(rootNames[index].toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Convenience: ratio of successful {@code NormalNoise} inlines to the total
     * (inlines + bind failures). {@code 1.0} means every visited sampler made it
     * into bytecode; values noticeably under {@code 1.0} indicate a binding regression
     * worth investigating. Returns {@code 1.0} when no noises have been visited yet
     * to avoid a misleading "0% inline rate" splash on a freshly booted server.
     */
    public static double noiseInlineRate() {
        long inlined = NOISES_INLINED_TOTAL.get();
        long failed = NoiseSpecCache.MIXIN_BIND_FAILURES.get();
        long denom = inlined + failed;
        return denom == 0 ? 1.0 : ((double) inlined) / ((double) denom);
    }

    /** Same as {@link #noiseInlineRate()} for {@code BlendedNoise}. */
    public static double blendedInlineRate() {
        long inlined = BLENDED_NOISES_INLINED_TOTAL.get();
        long failed = BlendedNoiseSpecCache.MIXIN_BIND_FAILURES.get();
        long denom = inlined + failed;
        return denom == 0 ? 1.0 : ((double) inlined) / ((double) denom);
    }
}
