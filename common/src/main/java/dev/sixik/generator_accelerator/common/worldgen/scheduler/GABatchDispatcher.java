package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import dev.sixik.generator_accelerator.GeneratorAccelerator;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class GABatchDispatcher implements Runnable {
    private final MpscRunnableRing pending;
    private final Thread thread;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong maxQueueDepth = new AtomicLong();
    private volatile boolean parked;
    private volatile boolean shutdown;

    public GABatchDispatcher(String name, int capacity) {
        this.pending = new MpscRunnableRing(Math.max(2, capacity));
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public boolean submit(Runnable task) {
        submitted.incrementAndGet();
        if (shutdown || task == null) {
            rejected.incrementAndGet();
            return false;
        }
        boolean offered = pending.offer(task);
        if (!offered) {
            rejected.incrementAndGet();
            return false;
        }
        accepted.incrementAndGet();
        updateMax(maxQueueDepth, pending.sizeEstimate());
        if (parked) {
            LockSupport.unpark(thread);
        }
        return true;
    }

    public void shutdown() {
        shutdown = true;
        LockSupport.unpark(thread);
    }

    public void join(long millis) throws InterruptedException {
        thread.join(millis);
    }

    public int pending() {
        return pending.sizeEstimate();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alive", thread.isAlive());
        out.put("shutdown", shutdown);
        out.put("pending", pending.sizeEstimate());
        out.put("submitted", submitted.get());
        out.put("accepted", accepted.get());
        out.put("rejected", rejected.get());
        out.put("completed", completed.get());
        out.put("failed", failed.get());
        out.put("maxQueueDepth", maxQueueDepth.get());
        out.put("lockFreeQueue", true);
        return out;
    }

    @Override
    public void run() {
        while (!shutdown || pending.sizeEstimate() > 0) {
            Runnable task = pending.poll();
            if (task == null) {
                idle();
                continue;
            }
            try {
                task.run();
                completed.incrementAndGet();
            } catch (Throwable throwable) {
                failed.incrementAndGet();
                GeneratorAccelerator.LOGGER.warn("GA v2 batch dispatcher task failed", throwable);
            }
        }
    }

    private void idle() {
        for (int i = 0; i < 32; i++) {
            Thread.onSpinWait();
            if (pending.sizeEstimate() > 0 || shutdown) {
                return;
            }
        }
        parked = true;
        try {
            LockSupport.parkNanos(100_000L);
        } finally {
            parked = false;
        }
    }

    private static void updateMax(AtomicLong value, long next) {
        long current;
        do {
            current = value.get();
            if (next <= current) {
                return;
            }
        } while (!value.compareAndSet(current, next));
    }

    /**
     * Bounded MPSC ring with per-slot sequence numbers. Single consumer is the
     * dispatcher thread; producers are server/worker threads. No locks, no monitor
     * waits, explicit false on pressure.
     */
    static final class MpscRunnableRing {
        private static final VarHandle SEQUENCE = MethodHandles.arrayElementVarHandle(long[].class);
        private static final VarHandle VALUES = MethodHandles.arrayElementVarHandle(Runnable[].class);

        private final int mask;
        private final long[] sequence;
        private final Runnable[] values;
        private final AtomicLong producer = new AtomicLong();
        private final AtomicLong consumer = new AtomicLong();

        MpscRunnableRing(int capacity) {
            int actualCapacity = GAReadyQueue.nextPowerOfTwo(Math.max(2, capacity));
            this.mask = actualCapacity - 1;
            this.sequence = new long[actualCapacity];
            this.values = new Runnable[actualCapacity];
            for (int i = 0; i < actualCapacity; i++) {
                sequence[i] = i;
            }
        }

        boolean offer(Runnable value) {
            for (;;) {
                long pos = producer.get();
                int slot = (int) pos & mask;
                long seq = (long) SEQUENCE.getAcquire(sequence, slot);
                long diff = seq - pos;
                if (diff == 0L) {
                    if (producer.compareAndSet(pos, pos + 1L)) {
                        VALUES.setRelease(values, slot, value);
                        SEQUENCE.setRelease(sequence, slot, pos + 1L);
                        return true;
                    }
                } else if (diff < 0L) {
                    return false;
                } else {
                    Thread.onSpinWait();
                }
            }
        }

        Runnable poll() {
            long pos = consumer.get();
            int slot = (int) pos & mask;
            long seq = (long) SEQUENCE.getAcquire(sequence, slot);
            long diff = seq - (pos + 1L);
            if (diff == 0L) {
                if (consumer.compareAndSet(pos, pos + 1L)) {
                    Runnable value = (Runnable) VALUES.getAcquire(values, slot);
                    VALUES.setRelease(values, slot, null);
                    SEQUENCE.setRelease(sequence, slot, pos + values.length);
                    return value;
                }
                return null;
            }
            return null;
        }

        int sizeEstimate() {
            long size = producer.get() - consumer.get();
            if (size <= 0L) {
                return 0;
            }
            return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
        }
    }
}
