package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/** Registry of optional late GPU-payload encoders for opaque density functions. */
public final class DensityFunctionGpuPayloadBuilderRegistry {
    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final LongAdder MATCHES = new LongAdder();
    private static final LongAdder ENCODED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private DensityFunctionGpuPayloadBuilderRegistry() {
    }

    /**
     * Register a payload builder for a loaded class. Superclass/interface matches are
     * allowed; first builder returning a non-negative payload node wins.
     */
    public static <T extends DensityFunction> Registration register(
            String id,
            Class<T> type,
            DensityFunctionGpuPayloadBuilder<? super T> builder) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(builder, "builder");
        Entry entry = new ClassEntry<>(normalizeId(id, type.getName()), type, builder);
        ENTRIES.add(entry);
        return () -> ENTRIES.remove(entry);
    }

    /**
     * Register by binary class name for optional mod compat where the target class may
     * not be present at compile time.
     */
    public static Registration registerByClassName(
            String id,
            String className,
            DensityFunctionGpuPayloadBuilder<DensityFunction> builder) {
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
        ENCODED.reset();
        FAILURES.reset();
    }

    public static Stats snapshotStats() {
        return new Stats(ENTRIES.size(), MATCHES.sum(), ENCODED.sum(), FAILURES.sum(),
                ENTRIES.stream().map(Entry::id).toList());
    }

    public static boolean hasBuilderFor(DensityFunction function) {
        if (function == null || ENTRIES.isEmpty()) {
            return false;
        }
        for (Entry entry : ENTRIES) {
            if (entry.matches(function) && supports(entry, function)) {
                return true;
            }
        }
        return false;
    }

    static int tryBuild(DensityFunction function, DensityFunctionGpuPayloadBuilder.Context context) {
        if (function == null || ENTRIES.isEmpty()) {
            return -1;
        }
        for (Entry entry : ENTRIES) {
            if (!entry.matches(function) || !supports(entry, function)) {
                continue;
            }
            MATCHES.increment();
            try {
                int encoded = entry.build(function, context);
                if (encoded >= 0) {
                    ENCODED.increment();
                    return encoded;
                }
            } catch (Throwable t) {
                FAILURES.increment();
                DensityFunctionCompiler.LOGGER.warn(
                        "DFC GPU payload builder '{}' failed for {} ({}); falling back to unsupported payload",
                        entry.id(), function.getClass().getName(), System.identityHashCode(function), t);
            }
        }
        return -1;
    }

    private static boolean supports(Entry entry, DensityFunction function) {
        try {
            return entry.supports(function);
        } catch (Throwable t) {
            FAILURES.increment();
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC GPU payload builder '{}' support check failed for {} ({}); treating as unsupported",
                    entry.id(), function.getClass().getName(), System.identityHashCode(function), t);
            return false;
        }
    }

    private static String normalizeId(String id, String fallback) {
        return id == null || id.isBlank() ? fallback : id.trim();
    }

    @FunctionalInterface
    public interface Registration {
        void unregister();
    }

    public record Stats(int registeredBuilders, long matches, long encoded, long failures, List<String> builderIds) {
    }

    private sealed interface Entry permits ClassEntry, ClassNameEntry {
        String id();

        boolean matches(DensityFunction function);

        boolean supports(DensityFunction function);

        int build(DensityFunction function, DensityFunctionGpuPayloadBuilder.Context context);
    }

    private record ClassEntry<T extends DensityFunction>(
            String id,
            Class<T> type,
            DensityFunctionGpuPayloadBuilder<? super T> builder) implements Entry {
        @Override
        public boolean matches(DensityFunction function) {
            return type.isInstance(function);
        }

        @Override
        public boolean supports(DensityFunction function) {
            return builder.supports(type.cast(function));
        }

        @Override
        public int build(DensityFunction function, DensityFunctionGpuPayloadBuilder.Context context) {
            return builder.build(type.cast(function), context);
        }
    }

    private record ClassNameEntry(
            String id,
            String className,
            DensityFunctionGpuPayloadBuilder<DensityFunction> builder) implements Entry {
        @Override
        public boolean matches(DensityFunction function) {
            return function.getClass().getName().equals(className);
        }

        @Override
        public boolean supports(DensityFunction function) {
            return builder.supports(function);
        }

        @Override
        public int build(DensityFunction function, DensityFunctionGpuPayloadBuilder.Context context) {
            return builder.build(function, context);
        }
    }
}
