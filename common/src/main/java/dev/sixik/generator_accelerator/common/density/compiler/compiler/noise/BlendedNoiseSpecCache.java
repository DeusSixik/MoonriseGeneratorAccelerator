package dev.sixik.generator_accelerator.common.density.compiler.compiler.noise;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.mixin.noise.BlendedNoiseAccessor;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identity-keyed {@link BlendedNoiseSpec} cache for the blended-noise inliner.
 */
public final class BlendedNoiseSpecCache {
    public static final AtomicLong SPECS_BUILT = new AtomicLong();
    public static final AtomicLong MIXIN_BIND_FAILURES = new AtomicLong();

    private static final Object FAILED = new Object();
    private static final ConcurrentHashMap<BlendedNoise, Object> CACHE = new ConcurrentHashMap<>();

    private BlendedNoiseSpecCache() {}

    public static BlendedNoiseSpec specFor(BlendedNoise noise) {
        Object v = CACHE.computeIfAbsent(noise, k -> {
            BlendedNoiseSpec b = build(k);
            if (b == null) {
                return FAILED;
            }
            SPECS_BUILT.incrementAndGet();
            return b;
        });
        if (v == FAILED) {
            return null;
        }
        return (BlendedNoiseSpec) v;
    }

    public static void clear() {
        CACHE.clear();
    }

    private static BlendedNoiseSpec build(BlendedNoise bn) {
        BlendedNoiseAccessor a;
        try {
            a = (BlendedNoiseAccessor) (Object) bn;
        } catch (ClassCastException ex) {
            DensityFunctionCompiler.LOGGER.warn(
                    "BlendedNoiseAccessor mixin not applied — falling back to IR Invoke",
                    System.identityHashCode(bn));
            MIXIN_BIND_FAILURES.incrementAndGet();
            return null;
        }
        PerlinNoise minP = a.dfc$getMinLimitNoise();
        PerlinNoise maxP = a.dfc$getMaxLimitNoise();
        PerlinNoise mainP = a.dfc$getMainNoise();
        if (minP == null || maxP == null || mainP == null) {
            return null;
        }
        double xzMul = a.dfc$getXzMultiplier();
        double yMul = a.dfc$getYMultiplier();
        double xzF = a.dfc$getXzFactor();
        double yF = a.dfc$getYFactor();
        double smear = a.dfc$getSmearScaleMultiplier();
        double maxV = a.dfc$getMaxValue();

        ImprovedNoise[] main = new ImprovedNoise[BlendedNoiseSpec.MAIN_OCTAVES];
        for (int i = 0; i < main.length; i++) {
            main[i] = mainP.getOctaveNoise(i);
        }
        ImprovedNoise[] minA = new ImprovedNoise[BlendedNoiseSpec.LIMIT_OCTAVES];
        ImprovedNoise[] maxA = new ImprovedNoise[BlendedNoiseSpec.LIMIT_OCTAVES];
        for (int j = 0; j < BlendedNoiseSpec.LIMIT_OCTAVES; j++) {
            minA[j] = minP.getOctaveNoise(j);
            maxA[j] = maxP.getOctaveNoise(j);
        }
        return new BlendedNoiseSpec(xzMul, yMul, xzF, yF, smear, maxV, main, minA, maxA);
    }
}
