package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/** Registry of optional {@link DensityFunctionIrBuilder} lowerings. */
public final class DensityFunctionIrBuilderRegistry {
    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final LongAdder MATCHES = new LongAdder();
    private static final LongAdder LOWERED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private DensityFunctionIrBuilderRegistry() {
    }

    /**
     * Register a builder for a loaded class. Superclass/interface matches are allowed;
     * first non-null builder result wins.
     */
    public static <T extends DensityFunction> Registration register(
            String id,
            Class<T> type,
            DensityFunctionIrBuilder<? super T> builder) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(builder, "builder");
        Entry entry = new ClassEntry(normalizeId(id, type.getName()), type, builder);
        ENTRIES.add(entry);
        return () -> ENTRIES.remove(entry);
    }

    /**
     * Register by binary class name. This is useful for optional mod compat where the
     * target class may not be available at compile time.
     */
    public static Registration registerByClassName(
            String id,
            String className,
            DensityFunctionIrBuilder<DensityFunction> builder) {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(builder, "builder");
        Entry entry = new ClassNameEntry(normalizeId(id, className), className, builder);
        ENTRIES.add(entry);
        return () -> ENTRIES.remove(entry);
    }

    /** Clears all dynamic registrations. Intended for tests / lifecycle reset only. */
    public static void clear() {
        ENTRIES.clear();
        MATCHES.reset();
        LOWERED.reset();
        FAILURES.reset();
    }

    public static Stats snapshotStats() {
        return new Stats(ENTRIES.size(), MATCHES.sum(), LOWERED.sum(), FAILURES.sum(),
                ENTRIES.stream().map(Entry::id).toList());
    }

    static IRNode tryBuild(DensityFunction function, DensityFunctionIrBuilder.Context context) {
        if (function == null || ENTRIES.isEmpty()) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (!entry.matches(function)) {
                continue;
            }
            MATCHES.increment();
            try {
                IRNode lowered = entry.build(function, context);
                if (lowered != null) {
                    LOWERED.increment();
                    return lowered;
                }
            } catch (Throwable t) {
                FAILURES.increment();
                DensityFunctionCompiler.LOGGER.warn(
                        "DFC IR builder '{}' failed for {} ({}); falling back to opaque invoke",
                        entry.id(), function.getClass().getName(), System.identityHashCode(function), t);
            }
        }
        return null;
    }

    private static String normalizeId(String id, String fallback) {
        return id == null || id.isBlank() ? fallback : id.trim();
    }

    @FunctionalInterface
    public interface Registration {
        void unregister();
    }

    public record Stats(int registeredBuilders, long matches, long lowered, long failures, List<String> builderIds) {
    }

    private sealed interface Entry permits ClassEntry, ClassNameEntry {
        String id();

        boolean matches(DensityFunction function);

        IRNode build(DensityFunction function, DensityFunctionIrBuilder.Context context);
    }

    private record ClassEntry<T extends DensityFunction>(
            String id,
            Class<T> type,
            DensityFunctionIrBuilder<? super T> builder) implements Entry {
        @Override
        public boolean matches(DensityFunction function) {
            return type.isInstance(function);
        }

        @Override
        public IRNode build(DensityFunction function, DensityFunctionIrBuilder.Context context) {
            return builder.build(type.cast(function), context);
        }
    }

    private record ClassNameEntry(
            String id,
            String className,
            DensityFunctionIrBuilder<DensityFunction> builder) implements Entry {
        @Override
        public boolean matches(DensityFunction function) {
            return function.getClass().getName().equals(className);
        }

        @Override
        public IRNode build(DensityFunction function, DensityFunctionIrBuilder.Context context) {
            return builder.build(function, context);
        }
    }
}
