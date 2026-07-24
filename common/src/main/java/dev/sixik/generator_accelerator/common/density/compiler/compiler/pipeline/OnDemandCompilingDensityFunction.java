package dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.concurrent.atomic.LongAdder;

/**
 * A {@link NoiseRouter} field that defers {@link CompilingVisitor#apply} until the first
 * time it is used. The wired source is post-{@code mapAll(NoiseWiringHelper)} on
 * {@link net.minecraft.world.level.levelgen.RandomState} &mdash; the same as batch
 * {@link RouterPipeline#compile}.
 *
 * <p><strong>Rebinding ({@code mapAll}):</strong> a naive implementation that wrapped
 * every remapped sub-root in a new on-demand instance multiplied compiles and hidden
 * classes in hot paths (NoiseChunk, chunk gen) that walk the tree many times. We
 * therefore call {@link #ensureResolved()} before any {@code mapAll} and delegate
 * to the already-compiled tree, matching {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction}
 * semantics: one compile per top-level field, then rebinds run on the compiled root.
 */
public final class OnDemandCompilingDensityFunction implements DensityFunction {

    private static final LongAdder WRAPPERS_CREATED = new LongAdder();
    private static final LongAdder COMPUTE_RESOLVE_ATTEMPTS = new LongAdder();
    private static final LongAdder FILL_ARRAY_RESOLVE_ATTEMPTS = new LongAdder();
    private static final LongAdder MAP_ALL_RESOLVE_ATTEMPTS = new LongAdder();
    private static final LongAdder MIN_VALUE_RESOLVE_ATTEMPTS = new LongAdder();
    private static final LongAdder MAX_VALUE_RESOLVE_ATTEMPTS = new LongAdder();
    private static final LongAdder SUCCESSFUL_COMPILES = new LongAdder();
    private static final LongAdder COMPILE_FAILURES = new LongAdder();
    private static final LongAdder COMPILE_FALLBACKS = new LongAdder();

    private final DensityFunction wired;

    private volatile DensityFunction resolved;

    public OnDemandCompilingDensityFunction(DensityFunction wired) {
        this.wired = wired;
        WRAPPERS_CREATED.increment();
    }

    public DensityFunction wired() {
        return wired;
    }

    private void ensureResolved(ResolveTrigger trigger) {
        if (resolved != null) {
            return;
        }
        recordResolveAttempt(trigger);
        synchronized (this) {
            if (resolved == null) {
                DensityFunction compiled;
                try {
                    compiled = CompilingVisitor.global().apply(wired);
                } catch (Throwable t) {
                    COMPILE_FAILURES.increment();
                    throw t;
                }
                if (compiled == wired) {
                    COMPILE_FALLBACKS.increment();
                } else {
                    SUCCESSFUL_COMPILES.increment();
                }
                resolved = compiled;
            }
        }
    }

    @Override
    public double compute(FunctionContext functionContext) {
        ensureResolved(ResolveTrigger.COMPUTE);
        return resolved.compute(functionContext);
    }

    @Override
    public void fillArray(double[] output, ContextProvider contextProvider) {
        ensureResolved(ResolveTrigger.FILL_ARRAY);
        resolved.fillArray(output, contextProvider);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Never allocate another OnDemand here: high-churn rebinds would each get a
        // first-use compile, exploding /dfc roots and world-load time.
        ensureResolved(ResolveTrigger.MAP_ALL);
        return resolved.mapAll(visitor);
    }

    @Override
    public double minValue() {
        ensureResolved(ResolveTrigger.MIN_VALUE);
        return resolved.minValue();
    }

    @Override
    public double maxValue() {
        ensureResolved(ResolveTrigger.MAX_VALUE);
        return resolved.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        if (resolved != null) {
            return resolved.codec();
        }
        return wired.codec();
    }

    private static void recordResolveAttempt(ResolveTrigger trigger) {
        switch (trigger) {
            case COMPUTE -> COMPUTE_RESOLVE_ATTEMPTS.increment();
            case FILL_ARRAY -> FILL_ARRAY_RESOLVE_ATTEMPTS.increment();
            case MAP_ALL -> MAP_ALL_RESOLVE_ATTEMPTS.increment();
            case MIN_VALUE -> MIN_VALUE_RESOLVE_ATTEMPTS.increment();
            case MAX_VALUE -> MAX_VALUE_RESOLVE_ATTEMPTS.increment();
        }
    }

    public static LazyStats snapshotLazyStats() {
        return new LazyStats(
                WRAPPERS_CREATED.sum(),
                COMPUTE_RESOLVE_ATTEMPTS.sum(),
                FILL_ARRAY_RESOLVE_ATTEMPTS.sum(),
                MAP_ALL_RESOLVE_ATTEMPTS.sum(),
                MIN_VALUE_RESOLVE_ATTEMPTS.sum(),
                MAX_VALUE_RESOLVE_ATTEMPTS.sum(),
                SUCCESSFUL_COMPILES.sum(),
                COMPILE_FAILURES.sum(),
                COMPILE_FALLBACKS.sum());
    }

    public static void resetLazyStats() {
        WRAPPERS_CREATED.reset();
        COMPUTE_RESOLVE_ATTEMPTS.reset();
        FILL_ARRAY_RESOLVE_ATTEMPTS.reset();
        MAP_ALL_RESOLVE_ATTEMPTS.reset();
        MIN_VALUE_RESOLVE_ATTEMPTS.reset();
        MAX_VALUE_RESOLVE_ATTEMPTS.reset();
        SUCCESSFUL_COMPILES.reset();
        COMPILE_FAILURES.reset();
        COMPILE_FALLBACKS.reset();
    }

    private enum ResolveTrigger {
        COMPUTE,
        FILL_ARRAY,
        MAP_ALL,
        MIN_VALUE,
        MAX_VALUE
    }

    public record LazyStats(long wrappersCreated,
                            long computeResolveAttempts,
                            long fillArrayResolveAttempts,
                            long mapAllResolveAttempts,
                            long minValueResolveAttempts,
                            long maxValueResolveAttempts,
                            long successfulCompiles,
                            long compileFailures,
                            long compileFallbacks) {
        public long totalResolveAttempts() {
            return computeResolveAttempts + fillArrayResolveAttempts + mapAllResolveAttempts
                    + minValueResolveAttempts + maxValueResolveAttempts;
        }
    }
}
