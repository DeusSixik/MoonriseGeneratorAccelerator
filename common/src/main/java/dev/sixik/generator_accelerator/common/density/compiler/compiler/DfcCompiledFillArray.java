package dev.sixik.generator_accelerator.common.density.compiler.compiler;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * On-demand vector fill bridge for vanilla DensityFunction nodes whose own
 * fillArray implementations otherwise stay in profiles after router compilation.
 */
public final class DfcCompiledFillArray {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "dfc.onDemandFillArray.enabled",
            "true"
    ));
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder COMPILED_HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> SOURCE_CLASSES = new ConcurrentHashMap<>();

    private DfcCompiledFillArray() {
    }

    public static boolean tryFillArray(DensityFunction source, double[] values, DensityFunction.ContextProvider provider) {
        if (!ENABLED || source instanceof CompiledDensityFunction) {
            return false;
        }
        ATTEMPTS.increment();
        SOURCE_CLASSES.computeIfAbsent(source.getClass().getName(), ignored -> new LongAdder()).increment();
        DensityFunction compiled = compile(source);
        if (compiled == source) {
            MISSES.increment();
            return false;
        }
        COMPILED_HITS.increment();
        compiled.fillArray(values, provider);
        return true;
    }

    public static DensityFunction compile(DensityFunction source) {
        if (!ENABLED || source instanceof CompiledDensityFunction) {
            return source;
        }
        return CompilingVisitor.global().apply(source);
    }

    public static void resetMetrics() {
        ATTEMPTS.reset();
        COMPILED_HITS.reset();
        MISSES.reset();
        SOURCE_CLASSES.clear();
    }

    public static Stats snapshot() {
        return new Stats(ENABLED, ATTEMPTS.sum(), COMPILED_HITS.sum(), MISSES.sum(), snapshotSourceClasses());
    }

    private static List<String> snapshotSourceClasses() {
        List<SourceClassStats> raw = new ArrayList<>(SOURCE_CLASSES.size());
        SOURCE_CLASSES.forEach((name, counter) -> raw.add(new SourceClassStats(name, counter.sum())));
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

    public record Stats(boolean enabled, long attempts, long compiledHits, long misses, List<String> sourceClasses) {
    }

    private record SourceClassStats(String className, long calls) {
    }
}
