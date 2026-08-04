package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Registry for custom GPU payload op descriptors. */
public final class DensityFunctionGpuKernelOpRegistry {
    private static final AtomicInteger NEXT_SLOT = new AtomicInteger();
    private static final ConcurrentHashMap<String, Entry> BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Entry> BY_SLOT = new ConcurrentHashMap<>();
    private static final LongAdder LOOKUPS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private DensityFunctionGpuKernelOpRegistry() {
    }

    public static Registration register(DensityFunctionGpuKernelOp op) {
        Objects.requireNonNull(op, "op");
        int slot = NEXT_SLOT.getAndIncrement();
        Entry entry = new Entry(slot, op);
        Entry previous = BY_ID.putIfAbsent(op.id(), entry);
        if (previous != null) {
            throw new IllegalArgumentException("Custom GPU op already registered: " + op.id());
        }
        BY_SLOT.put(slot, entry);
        return () -> {
            BY_ID.remove(op.id(), entry);
            BY_SLOT.remove(slot, entry);
        };
    }

    public static Entry lookup(String id) {
        LOOKUPS.increment();
        Entry entry = id == null || id.isBlank() ? null : BY_ID.get(id.trim());
        if (entry == null) {
            MISSES.increment();
        }
        return entry;
    }

    static Entry lookupSlot(int slot) {
        LOOKUPS.increment();
        Entry entry = BY_SLOT.get(slot);
        if (entry == null) {
            MISSES.increment();
        }
        return entry;
    }

    /** Clears all dynamic registrations. Intended for tests / lifecycle reset only. */
    public static void clear() {
        BY_ID.clear();
        BY_SLOT.clear();
        NEXT_SLOT.set(0);
        LOOKUPS.reset();
        MISSES.reset();
    }

    public static Stats snapshotStats() {
        List<Entry> entries = new ArrayList<>(BY_ID.values());
        entries.sort(Comparator.comparing(entry -> entry.op().id()));
        return new Stats(
                entries.size(),
                LOOKUPS.sum(),
                MISSES.sum(),
                entries.stream().filter(entry -> entry.op().hasOpenClExpression()).count(),
                entries.stream().map(entry -> entry.op().id()).toList());
    }

    @FunctionalInterface
    public interface Registration {
        void unregister();
    }

    public record Entry(int slot, DensityFunctionGpuKernelOp op) {
    }

    public record Stats(long registeredOps, long lookups, long misses, long sourceFragments, List<String> opIds) {
    }
}
