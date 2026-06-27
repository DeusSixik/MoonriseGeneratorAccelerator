package dev.sixik.generator_accelerator.common.density.compiler.compiler.noise;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.mixin.noise.NormalNoiseAccessor;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide cache of {@link NoiseSpec} instances keyed by {@link NormalNoise}
 * identity. Each {@code NormalNoise} occurs in many density functions across many
 * compiled noise routers, so the per-spec build (a couple of mixin reads, an array
 * walk, a few {@link Math#pow} calls) only happens once per unique sampler.
 *
 * <p>Identity-keyed because vanilla {@code NormalNoise} doesn't override
 * {@link Object#equals}, and identical noise parameters can produce instances that
 * are observably different (different permutation tables from different RandomState
 * forks). This matches how {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool#internNoise}
 * stores noise references downstream.
 *
 * <p>Thread-safety: a {@link ConcurrentHashMap} allows concurrent {@link #specFor}
 * lookups; each key is built at most once (failed builds are cached as a sentinel
 * so we do not keep retrying under contention).
 *
 * <p>Stats are exposed through the static atomics so {@code /dfc stats} and
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline}
 * can surface them without taking the cache lock.
 */
public final class NoiseSpecCache {

    /** Total number of {@link NoiseSpec} instances built (lifetime). */
    public static final AtomicLong SPECS_BUILT = new AtomicLong();
    /** Sum of active octaves across every built spec (lifetime). */
    public static final AtomicLong OCTAVES_ACTIVE = new AtomicLong();
    /** Sum of skipped (null/zero-amplitude) octaves across every built spec. */
    public static final AtomicLong OCTAVES_SKIPPED = new AtomicLong();
    /** Number of spec builds that hit a mixin-binding failure and bailed. */
    public static final AtomicLong MIXIN_BIND_FAILURES = new AtomicLong();

    private static final Object FAILED = new Object();

    private static final Cache<NormalNoise, Object> CACHE = Caffeine.newBuilder()
            .initialCapacity(32)
            .weakKeys()
            .build();

    private NoiseSpecCache() {}

    /**
     * Returns the cached spec for {@code noise}, building (and caching) it on first
     * call. Returns {@code null} when the spec cannot be built (mixin-binding
     * failure, malformed PerlinNoise state); callers are expected to fall back to
     * the legacy un-inlined emission path in that case.
     */
    public static NoiseSpec specFor(NormalNoise noise) {
        Object v = CACHE.get(noise, k -> {
            NoiseSpec built = build(k);
            if (built == null) {
                return FAILED;
            }
            SPECS_BUILT.incrementAndGet();
            OCTAVES_ACTIVE.addAndGet(built.totalActiveOctaves());
            return built;
        });
        if (v == FAILED) {
            return null;
        }
        return (NoiseSpec) v;
    }

    /** For tests / {@code /dfc reload}: drop everything we've cached. */
    public static void clear() {
        CACHE.invalidateAll();
        CACHE.cleanUp();
        BlendedNoiseSpecCache.clear();
    }

    static void recordPerlinAccessorFailure() {
        MIXIN_BIND_FAILURES.incrementAndGet();
    }

    static void addOctavesSkippedForPerlinBuild(int skipped) {
        OCTAVES_SKIPPED.addAndGet(skipped);
    }

    /**
     * Hard ceiling on the per-noise active-octave budget that may be unrolled inline.
     * A {@code NormalNoise} with {@code first.activeOctaves + second.activeOctaves}
     * exceeding this number would, on its own, blow past half of {@link
     * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Splitter#METHOD_BUDGET_BYTES}
     * — at which point the splitter still has to evict something but the inlined
     * noise can't itself be hoisted into a helper without bringing along its octave
     * fields. The threshold is sized against the per-octave estimate in {@link
     * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.SizeEstimator}
     * (~65 bytes per octave): {@code 25_000 / 65 ≈ 384}. Vanilla noises top out
     * around 16 octaves combined, so this is purely a defensive cap for mod-defined
     * noises with pathological octave counts.
     */
    public static final int MAX_INLINEABLE_OCTAVES = 384;

    /**
     * Returns {@code true} if the given spec is small enough to safely inline. A
     * {@code false} return tells {@link
     * dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.NoiseExpander}
     * to keep emitting the legacy {@code INVOKEVIRTUAL NormalNoise.getValue} path
     * for this noise, preserving correctness when the inlined form would exceed the
     * per-method bytecode budget.
     */
    public static boolean shouldInline(NoiseSpec spec) {
        if (spec == null) return false;
        return spec.totalActiveOctaves() <= MAX_INLINEABLE_OCTAVES;
    }

    private static NoiseSpec build(NormalNoise noise) {
        NormalNoiseAccessor nn;
        try {
            nn = (NormalNoiseAccessor) (Object) noise;
        } catch (ClassCastException ex) {
            // Mixin didn't apply (development env without -Dmixin.debug=true,
            // or a third-party agent stripped our class transformations).
            DensityFunctionCompiler.LOGGER.warn(
                    "NormalNoiseAccessor mixin not applied to {} — falling back to legacy noise emission",
                    System.identityHashCode(noise));
            MIXIN_BIND_FAILURES.incrementAndGet();
            return null;
        }
        PerlinNoise first = nn.dfc$getFirst();
        PerlinNoise second = nn.dfc$getSecond();
        double valueFactor = nn.dfc$getValueFactor();
        if (first == null || second == null) {
            // Defensive: NormalNoise's ctor never leaves these null, but if a mod
            // ever subclasses with a degenerate state we'd rather fall back than
            // NPE inside generated bytecode.
            return null;
        }
        NoiseSpec.PerlinSpec firstSpec = PerlinSpecBuilder.build(first, /* inputCoordScale */ 1.0);
        NoiseSpec.PerlinSpec secondSpec = PerlinSpecBuilder.build(second, NoiseSpec.INPUT_FACTOR);
        if (firstSpec == null || secondSpec == null) return null;
        return new NoiseSpec(valueFactor, firstSpec, secondSpec);
    }
}
