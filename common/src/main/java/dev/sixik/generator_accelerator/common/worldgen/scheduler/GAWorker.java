package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.treads.GAFastLocalHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

public final class GAWorker implements Runnable {
    private static final int MAX_RESUME_DRAIN = 64;
    private static final int MAX_INBOX_DRAIN = 128;

    private final GASchedulerRuntime runtime;
    private final int index;
    private final GAReadyQueue localReady;
    private final GAReadyQueue.LongRing inbox;
    private final GAReadyQueue.LongRing urgentResumeInbox;
    private final Thread thread;
    private final Object[] fastLocals = new Object[64];
    private int stealCursor;
    private volatile boolean parked;
    private volatile boolean shutdown;
    private volatile int activeDepth;

    GAWorker(GASchedulerRuntime runtime, int index, int queueCapacity, int maxParkNanos) {
        this.runtime = runtime;
        this.index = index;
        this.localReady = new GAReadyQueue(queueCapacity);
        this.inbox = new GAReadyQueue.LongRing(queueCapacity);
        this.urgentResumeInbox = new GAReadyQueue.LongRing(Math.max(256, queueCapacity >>> 2));
        this.thread = new WorkerThread(this, "GA-V2-" + index);
    }

    public GASchedulerRuntime runtime() {
        return runtime;
    }

    public int index() {
        return index;
    }

    public boolean isCurrentThread() {
        return Thread.currentThread() == thread;
    }

    public int activeDepth() {
        return activeDepth;
    }

    int stealCursorIncrement() {
        stealCursor += 0x9E37_79B9;
        return stealCursor;
    }

    void start() {
        thread.start();
    }

    void shutdown() {
        shutdown = true;
        LockSupport.unpark(thread);
    }

    void join(long millis) throws InterruptedException {
        thread.join(millis);
    }

    boolean alive() {
        return thread.isAlive();
    }

    boolean offerLocal(GATaskClass taskClass, long handle, boolean urgent) {
        boolean accepted = localReady.offer(taskClass, handle, urgent);
        if (accepted) {
            unparkIfNeeded();
        }
        return accepted;
    }

    boolean offerInbox(long handle, boolean urgent) {
        boolean accepted = urgent ? urgentResumeInbox.offer(handle) : inbox.offer(handle);
        if (accepted) {
            unparkIfNeeded();
        }
        return accepted;
    }

    long stealLocal() {
        return localReady.pollStealable();
    }

    int queueDepthEstimate() {
        return localReady.sizeEstimate() + inbox.sizeEstimate() + urgentResumeInbox.sizeEstimate();
    }

    Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        out.put("alive", thread.isAlive());
        out.put("parked", parked);
        out.put("activeDepth", activeDepth);
        out.put("local", localReady.snapshot());
        out.put("inbox", inbox.sizeEstimate());
        out.put("urgentResumeInbox", urgentResumeInbox.sizeEstimate());
        return out;
    }

    private void unparkIfNeeded() {
        if (parked) {
            LockSupport.unpark(thread);
        }
    }

    @Override
    public void run() {
        runtime.bindWorker(this);
        try {
            loop();
        } finally {
            runtime.unbindWorker();
        }
    }

    private void loop() {
        while (!shutdown && !runtime.shutdownRequested()) {
            drainUrgentResumeBatch(MAX_RESUME_DRAIN);

            long handle = pollLocalReady();
            if (handle == GAWorkHandle.NULL_HANDLE) {
                drainInboxBatch(MAX_INBOX_DRAIN);
                handle = pollLocalReady();
            }
            if (handle == GAWorkHandle.NULL_HANDLE) {
                handle = runtime.stealPolicy().trySteal(this);
            }
            if (handle == GAWorkHandle.NULL_HANDLE) {
                idle();
                continue;
            }
            runOrAdvance(handle);
        }
    }

    private void drainUrgentResumeBatch(int max) {
        for (int i = 0; i < max; i++) {
            long handle = urgentResumeInbox.poll();
            if (handle == GAWorkHandle.NULL_HANDLE) {
                return;
            }
            GATaskClass taskClass = runtime.taskClassForHandle(handle);
            if (!localReady.offer(taskClass, handle, true)) {
                runtime.metrics().recordDroppedHandle();
                return;
            }
        }
    }

    private void drainInboxBatch(int max) {
        for (int i = 0; i < max; i++) {
            long handle = inbox.poll();
            if (handle == GAWorkHandle.NULL_HANDLE) {
                return;
            }
            GATaskClass taskClass = runtime.taskClassForHandle(handle);
            if (!localReady.offer(taskClass, handle, GAWorkHandle.urgent(handle))) {
                runtime.metrics().recordDroppedHandle();
                return;
            }
        }
    }

    private long pollLocalReady() {
        long due = runtime.pollDue(this);
        if (due != GAWorkHandle.NULL_HANDLE) {
            return due;
        }
        return localReady.pollLocal();
    }

    private void runOrAdvance(long handle) {
        activeDepth++;
        try {
            runtime.runHandle(this, handle);
        } catch (Throwable throwable) {
            runtime.metrics().recordWorkerFatalError();
            runtime.disableAdmission("worker fatal error");
            GeneratorAccelerator.LOGGER.warn("GA v2 scheduler worker {} failed", thread.getName(), throwable);
        } finally {
            activeDepth--;
        }
    }

    private void idle() {
        long start = System.nanoTime();
        for (int i = 0; i < 16; i++) {
            Thread.onSpinWait();
            if (localReady.sizeEstimate() > 0 || inbox.sizeEstimate() > 0 || urgentResumeInbox.sizeEstimate() > 0) {
                runtime.metrics().addIdleNanos(System.nanoTime() - start);
                return;
            }
        }
        parked = true;
        try {
            LockSupport.parkNanos(runtime.config().maxParkNanos());
        } finally {
            parked = false;
            runtime.metrics().addIdleNanos(System.nanoTime() - start);
        }
    }

    private final class WorkerThread extends Thread implements GAFastLocalHolder {
        private WorkerThread(Runnable target, String name) {
            super(target, name);
            setDaemon(true);
        }

        @Override
        public Object[] gaFastLocals() {
            return fastLocals;
        }
    }
}
