package dev.sixik.generator_accelerator.common.surface_compiler.cache;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Epoch-scoped loader holder for generated kernels. */
public final class EpochClassLoader extends ClassLoader implements AutoCloseable {
    private static final AtomicLong NEXT_EPOCH = new AtomicLong(1L);
    private static final CopyOnWriteArrayList<EpochClassLoader> LIVE_LOADERS = new CopyOnWriteArrayList<>();

    private final long epoch;
    private final AtomicBoolean retired = new AtomicBoolean();

    public EpochClassLoader(long epoch, ClassLoader parent) {
        super(parent);
        this.epoch = epoch;
    }

    public static EpochClassLoader create(ClassLoader parent) {
        EpochClassLoader loader = new EpochClassLoader(NEXT_EPOCH.getAndIncrement(), parent);
        LIVE_LOADERS.add(loader);
        return loader;
    }

    public static void retireAll() {
        for (EpochClassLoader loader : LIVE_LOADERS) {
            loader.close();
        }
        LIVE_LOADERS.clear();
    }

    public static List<Long> liveEpochs() {
        List<Long> out = new ArrayList<>();
        for (EpochClassLoader loader : LIVE_LOADERS) {
            if (!loader.retired()) {
                out.add(loader.epoch());
            }
        }
        return List.copyOf(out);
    }

    public static int liveLoaderCount() {
        int count = 0;
        for (EpochClassLoader loader : LIVE_LOADERS) {
            if (!loader.retired()) {
                count++;
            }
        }
        return count;
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

    public Class<?> define(String name, byte[] bytecode) {
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
