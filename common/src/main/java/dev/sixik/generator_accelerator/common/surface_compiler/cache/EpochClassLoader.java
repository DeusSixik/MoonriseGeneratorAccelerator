package dev.sixik.generator_accelerator.common.surface_compiler.cache;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Epoch-scoped loader holder for generated kernels. */
public final class EpochClassLoader extends ClassLoader implements AutoCloseable {
    private static final AtomicLong NEXT_EPOCH = new AtomicLong(1L);
    private static final Object LOADER_LOCK = new Object();
    private static final AtomicReference<EpochClassLoader> CURRENT = new AtomicReference<>();

    private final long epoch;
    private final AtomicBoolean retired = new AtomicBoolean();

    public EpochClassLoader(long epoch, ClassLoader parent) {
        super(parent);
        this.epoch = epoch;
    }

    public static EpochClassLoader create(ClassLoader parent) {
        EpochClassLoader loader = CURRENT.get();
        if (loader != null && !loader.retired()) {
            return loader;
        }
        synchronized (LOADER_LOCK) {
            loader = CURRENT.get();
            if (loader != null && !loader.retired()) {
                return loader;
            }
            loader = new EpochClassLoader(NEXT_EPOCH.getAndIncrement(), parent);
            CURRENT.set(loader);
            return loader;
        }
    }

    public static void retireAll() {
        EpochClassLoader loader = CURRENT.getAndSet(null);
        if (loader != null) {
            loader.close();
        }
    }

    public static List<Long> liveEpochs() {
        List<Long> out = new ArrayList<>();
        EpochClassLoader loader = CURRENT.get();
        if (loader != null && !loader.retired()) {
            out.add(loader.epoch());
        }
        return List.copyOf(out);
    }

    public static int liveLoaderCount() {
        EpochClassLoader loader = CURRENT.get();
        return loader == null || loader.retired() ? 0 : 1;
    }

    public long epoch() {
        return this.epoch;
    }

    public boolean retired() {
        return this.retired.get();
    }

    public WeakReference<ClassLoader> weakHandle() {
        return new WeakReference<>(this);
    }

    public synchronized Class<?> define(String name, byte[] bytecode) {
        if (this.retired.get()) {
            throw new IllegalStateException("cannot define class in retired epoch " + this.epoch);
        }
        return defineClass(name, bytecode, 0, bytecode.length);
    }

    @Override
    public void close() {
        this.retired.set(true);
    }
}
