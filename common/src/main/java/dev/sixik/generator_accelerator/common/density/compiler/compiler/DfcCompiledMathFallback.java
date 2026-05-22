package dev.sixik.generator_accelerator.common.density.compiler.compiler;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Guarded compiler entry for NoiseChunk cache misses.
 *
 * <p>Cache2D/FlatCache are chunk-local, stateful wrappers. Compiling their wrapped
 * function unconditionally breaks identity-sensitive NoiseChunk semantics. This helper
 * only accepts subtrees that are fully mathematical in DFC IR and would not fall back
 * into an opaque DensityFunction/marker/beardifier/blender call.
 */
public final class DfcCompiledMathFallback {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "dfc.cacheMathFallback.enabled",
            "true"
    ));
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder ACCEPTED = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder COMPILED_HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> ACCEPTED_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> REJECTED_CLASSES = new ConcurrentHashMap<>();

    private DfcCompiledMathFallback() {
    }

    public static DensityFunction compileIfFullyMath(DensityFunction source) {
        if (!ENABLED) {
            return null;
        }
        ATTEMPTS.increment();
        if (source instanceof CompiledDensityFunction) {
            ACCEPTED.increment();
            COMPILED_HITS.increment();
            ACCEPTED_CLASSES.computeIfAbsent(source.getClass().getName(), ignored -> new LongAdder()).increment();
            return source;
        }
        DensityFunction target = mathCompileTarget(source);
        if (target == null) {
            REJECTED.increment();
            REJECTED_CLASSES.computeIfAbsent(source.getClass().getName(), ignored -> new LongAdder()).increment();
            return null;
        }
        ACCEPTED.increment();
        ACCEPTED_CLASSES.computeIfAbsent(source.getClass().getName(), ignored -> new LongAdder()).increment();
        DensityFunction compiled = DfcCompiledFillArray.compile(target);
        if (compiled == target) {
            MISSES.increment();
            return null;
        }
        COMPILED_HITS.increment();
        return compiled;
    }

    public static void resetMetrics() {
        ATTEMPTS.reset();
        ACCEPTED.reset();
        REJECTED.reset();
        COMPILED_HITS.reset();
        MISSES.reset();
        ACCEPTED_CLASSES.clear();
        REJECTED_CLASSES.clear();
    }

    public static Stats snapshot() {
        return new Stats(
                ENABLED,
                ATTEMPTS.sum(),
                ACCEPTED.sum(),
                REJECTED.sum(),
                COMPILED_HITS.sum(),
                MISSES.sum(),
                snapshotClasses(ACCEPTED_CLASSES),
                snapshotClasses(REJECTED_CLASSES)
        );
    }

    private static List<String> snapshotClasses(ConcurrentHashMap<String, LongAdder> source) {
        List<SourceClassStats> raw = new ArrayList<>(source.size());
        source.forEach((name, counter) -> raw.add(new SourceClassStats(name, counter.sum())));
        raw.sort(Comparator.comparingLong(SourceClassStats::calls).reversed().thenComparing(SourceClassStats::className));
        List<String> out = new ArrayList<>(raw.size());
        int limit = Math.min(raw.size(), 8);
        for (int i = 0; i < limit; i++) {
            SourceClassStats stat = raw.get(i);
            out.add(stat.className() + "=" + stat.calls());
        }
        return out;
    }

    private static DensityFunction mathCompileTarget(DensityFunction source) {
        if (source instanceof DensityFunctions.MarkerOrMarked marker) {
            try {
                return mathCompileTarget(marker.wrapped());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return isFullyMath(source) ? source : null;
    }

    private static boolean isFullyMath(DensityFunction df) {
        if (df instanceof CompiledDensityFunction) {
            return true;
        }
        if (df instanceof DensityFunctions.BlendAlpha
                || df instanceof DensityFunctions.BlendOffset
                || df == DensityFunctions.BeardifierMarker.INSTANCE
                || df instanceof DensityFunctions.BlendDensity
                || df instanceof Beardifier) {
            return false;
        }
        if (df instanceof DensityFunctions.HolderHolder hh) {
            try {
                return isFullyMath(hh.function().value());
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (df instanceof DensityFunctions.Constant
                || df instanceof DensityFunctions.YClampedGradient) {
            return true;
        }
        if (df instanceof DensityFunctions.Clamp c) {
            return isFullyMath(c.input());
        }
        if (df instanceof DensityFunctions.RangeChoice rc) {
            return isFullyMath(rc.input())
                    && isFullyMath(rc.whenInRange())
                    && isFullyMath(rc.whenOutOfRange());
        }
        if (df instanceof DensityFunctions.MulOrAdd ma) {
            return isFullyMath(ma.input());
        }
        if (df instanceof DensityFunctions.TwoArgumentSimpleFunction tas) {
            return isFullyMath(tas.argument1()) && isFullyMath(tas.argument2());
        }
        if (df instanceof DensityFunctions.Mapped mapped) {
            return isFullyMath(mapped.input());
        }
        if (df instanceof DensityFunctions.Noise noise) {
            return noise.noise().noise() != null;
        }
        if (df instanceof DensityFunctions.ShiftedNoise shifted) {
            return shifted.noise().noise() != null
                    && isFullyMath(shifted.shiftX())
                    && isFullyMath(shifted.shiftY())
                    && isFullyMath(shifted.shiftZ());
        }
        if (df instanceof DensityFunctions.ShiftA shiftA) {
            return shiftA.offsetNoise().noise() != null;
        }
        if (df instanceof DensityFunctions.ShiftB shiftB) {
            return shiftB.offsetNoise().noise() != null;
        }
        if (df instanceof DensityFunctions.Shift shift) {
            return shift.offsetNoise().noise() != null;
        }
        if (df instanceof DensityFunctions.WeirdScaledSampler weird) {
            return weird.noise().noise() != null && isFullyMath(weird.input());
        }
        if (df instanceof DensityFunctions.Spline) {
            return false;
        }
        return df instanceof BlendedNoise blended && df.getClass() == BlendedNoise.class;
    }

    public record Stats(boolean enabled, long attempts, long accepted, long rejected, long compiledHits, long misses,
                        List<String> acceptedClasses, List<String> rejectedClasses) {
    }

    private record SourceClassStats(String className, long calls) {
    }
}
