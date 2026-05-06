package dev.sixik.generator_accelerator.common.treads;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class GAThreadLocal<T> {
    private static final AtomicInteger INDEX_GENERATOR = new AtomicInteger(0);
    private static final Object NULL_SENTINEL = new Object();

    private final ThreadLocal<Object> fallback = new ThreadLocal<>();
    private final int index;
    private final Supplier<? extends T> supplier;

    private GAThreadLocal(Supplier<? extends T> supplier) {
        int idx = INDEX_GENERATOR.getAndIncrement();
        if (idx >= GAThread.FAST_THREAD_LOCAL_SIZE) {
            throw new IllegalStateException(
                    "GAThreadLocal limit reached: " + GAThread.FAST_THREAD_LOCAL_SIZE);
        }
        this.index = idx;
        this.supplier = supplier;
    }

    public static <T> GAThreadLocal<T> withInitial(Supplier<? extends T> s) {
        return new GAThreadLocal<>(s);
    }

    @SuppressWarnings("unchecked")
    public T get() {
        Thread t = Thread.currentThread();
        if (t instanceof GAFastLocalHolder) {
            Object[] arr = ((GAFastLocalHolder) t).gaFastLocals();
            Object v = arr[index];
            if (v != null) return v == NULL_SENTINEL ? null : (T) v;
            return initializeFast(arr);
        }
        return getSlow();
    }

    public void set(T value) {
        Thread t = Thread.currentThread();
        Object stored = (value == null) ? NULL_SENTINEL : value;
        if (t instanceof GAFastLocalHolder) {
            ((GAFastLocalHolder) t).gaFastLocals()[index] = stored;
            return;
        }
        fallback.set(stored);
    }

    public void remove() {
        Thread t = Thread.currentThread();
        if (t instanceof GAFastLocalHolder) {
            ((GAFastLocalHolder) t).gaFastLocals()[index] = null;
            return;
        }
        fallback.remove();
    }

    private T initializeFast(Object[] arr) {
        T v = (supplier != null) ? supplier.get() : null;
        arr[index] = (v == null) ? NULL_SENTINEL : v;
        return v;
    }

    @SuppressWarnings("unchecked")
    private T getSlow() {
        Object v = fallback.get();
        if (v == null) {
            T initial = (supplier != null) ? supplier.get() : null;
            fallback.set(initial == null ? NULL_SENTINEL : initial);
            return initial;
        }
        return v == NULL_SENTINEL ? null : (T) v;
    }
}