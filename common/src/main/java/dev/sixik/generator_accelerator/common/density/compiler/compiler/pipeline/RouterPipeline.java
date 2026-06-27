package dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final AtomicInteger CLASSES_ALIVE = new AtomicInteger();
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

    /**
     * Compiles each router / sampler top-level field in the GA compile lane. Running
     * inside that ForkJoinPool keeps {@code IntStream.parallel} off the common pool.
     */
    private RouterPipeline() {}

    private static void compileFieldsParallel(CompilingVisitor visitor, DensityFunction[] sources,
            DensityFunction[] compiled, int n, String failureKind) {
        try {
            IntStream.range(0, n).parallel().forEach(i -> {
                DensityFunction src = sources[i];
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
            compileFieldsSequential(visitor, sources, compiled, n, failureKind);
        }
    }

    private static void compileFieldsSequential(CompilingVisitor visitor, DensityFunction[] sources,
            DensityFunction[] compiled, int n, String failureKind) {
        for (int i = 0; i < n; i++) {
            DensityFunction src = sources[i];
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
        compileFieldsParallel(visitor, sources, compiled, sources.length, "compile");
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

    public static void recordCompiledRoot(int uniqueNodes, int csePostInternSavings) {
        ROOTS_COMPILED.incrementAndGet();
        CLASSES_ALIVE.incrementAndGet();
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

    public static void recordCompiledClassDropped() {
        CLASSES_ALIVE.decrementAndGet();
    }

    public record Stats(int rootsCompiled, int classesAlive, long uniqueNodes,
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

    public static Stats snapshotStats() {
        OnDemandCompilingDensityFunction.LazyStats lazy =
                OnDemandCompilingDensityFunction.snapshotLazyStats();
        return new Stats(
                ROOTS_COMPILED.get(),
                CLASSES_ALIVE.get(),
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
