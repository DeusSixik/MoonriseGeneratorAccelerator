package dev.sixik.generator_accelerator.common.treads;

import org.jetbrains.annotations.Nullable;

public class GAThread extends Thread implements GAFastLocalHolder {
    public static final int FAST_THREAD_LOCAL_SIZE = 4096;

    public final Object[] fastLocals = new Object[FAST_THREAD_LOCAL_SIZE];

    public GAThread(Runnable target, String name) {
        super(target, name);
    }

    public GAThread(ThreadGroup group, Runnable target, String name) {
        super(group, target, name);
    }

    public GAThread(ThreadGroup group, Runnable target, String name, long stackSize) {
        super(group, target, name, stackSize);
    }

    public GAThread(Runnable target) {
        super(target);
    }

    @Override
    public Object[] gaFastLocals() {
        return fastLocals;
    }

    public static boolean isGAThread() {
        return currentGAThread() != null;
    }

    @Nullable
    public static GAThread currentGAThread() {
        Thread t = Thread.currentThread();
        return (t instanceof GAThread) ? (GAThread) t : null;
    }
}
