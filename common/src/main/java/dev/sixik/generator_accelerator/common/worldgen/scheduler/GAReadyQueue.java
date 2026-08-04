package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded VarHandle-backed long queues. Zero is reserved as empty/null handle. */
public final class GAReadyQueue {
    private final LongRing urgent;
    private final LongRing hot;
    private final LongRing cpu;
    private final LongRing write;
    private final LongRing bg;

    public GAReadyQueue(int capacityPerTier) {
        int capacity = nextPowerOfTwo(Math.max(2, capacityPerTier));
        this.urgent = new LongRing(capacity);
        this.hot = new LongRing(capacity);
        this.cpu = new LongRing(capacity);
        this.write = new LongRing(Math.max(2, capacity >>> 1));
        this.bg = new LongRing(Math.max(2, capacity >>> 1));
    }

    public boolean offer(GATaskClass taskClass, long handle, boolean urgentHandle) {
        if (handle == GAWorkHandle.NULL_HANDLE) {
            return false;
        }
        if (urgentHandle) {
            return urgent.offer(handle);
        }
        return ringFor(taskClass).offer(handle);
    }

    public long pollUrgent() {
        return urgent.poll();
    }

    public long pollLocal() {
        long handle = urgent.poll();
        if (handle != GAWorkHandle.NULL_HANDLE) {
            return handle;
        }
        handle = hot.poll();
        if (handle != GAWorkHandle.NULL_HANDLE) {
            return handle;
        }
        handle = cpu.poll();
        if (handle != GAWorkHandle.NULL_HANDLE) {
            return handle;
        }
        handle = write.poll();
        if (handle != GAWorkHandle.NULL_HANDLE) {
            return handle;
        }
        return bg.poll();
    }

    public long pollStealable() {
        long handle = cpu.poll();
        if (handle != GAWorkHandle.NULL_HANDLE) {
            return handle;
        }
        handle = hot.poll();
        if (handle != GAWorkHandle.NULL_HANDLE) {
            return handle;
        }
        return bg.poll();
    }

    public int sizeEstimate() {
        return urgent.sizeEstimate() + hot.sizeEstimate() + cpu.sizeEstimate() + write.sizeEstimate() + bg.sizeEstimate();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("urgent", urgent.sizeEstimate());
        out.put("hot", hot.sizeEstimate());
        out.put("cpu", cpu.sizeEstimate());
        out.put("write", write.sizeEstimate());
        out.put("bg", bg.sizeEstimate());
        return out;
    }

    private LongRing ringFor(GATaskClass taskClass) {
        return switch (taskClass) {
            case CPU_NOISE -> hot;
            case CPU_WORKSPACE, BOUNDARY -> cpu;
            case WRITE_GUARDED, SERIAL_LEGACY, COMMIT_BOUNDARY -> write;
            case BG_COMPILE -> bg;
        };
    }

    public static int nextPowerOfTwo(int value) {
        int highest = Integer.highestOneBit(value);
        if (highest == value) {
            return value;
        }
        return highest >= (1 << 30) ? 1 << 30 : highest << 1;
    }

    public static final class LongRing {
        private static final VarHandle SEQUENCE = MethodHandles.arrayElementVarHandle(long[].class);
        private static final VarHandle VALUES = MethodHandles.arrayElementVarHandle(long[].class);

        private final int mask;
        private final long[] sequence;
        private final long[] values;
        private final AtomicLong producer = new AtomicLong();
        private final AtomicLong consumer = new AtomicLong();

        public LongRing(int capacityPowerOfTwo) {
            int capacity = nextPowerOfTwo(Math.max(2, capacityPowerOfTwo));
            this.mask = capacity - 1;
            this.sequence = new long[capacity];
            this.values = new long[capacity];
            for (int i = 0; i < capacity; i++) {
                sequence[i] = i;
            }
        }

        public boolean offer(long value) {
            if (value == GAWorkHandle.NULL_HANDLE) {
                return false;
            }
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

        public long poll() {
            for (;;) {
                long pos = consumer.get();
                int slot = (int) pos & mask;
                long seq = (long) SEQUENCE.getAcquire(sequence, slot);
                long diff = seq - (pos + 1L);
                if (diff == 0L) {
                    if (consumer.compareAndSet(pos, pos + 1L)) {
                        long value = (long) VALUES.getAcquire(values, slot);
                        VALUES.setRelease(values, slot, GAWorkHandle.NULL_HANDLE);
                        SEQUENCE.setRelease(sequence, slot, pos + values.length);
                        return value;
                    }
                } else if (diff < 0L) {
                    return GAWorkHandle.NULL_HANDLE;
                } else {
                    Thread.onSpinWait();
                }
            }
        }

        public int sizeEstimate() {
            long size = producer.get() - consumer.get();
            if (size <= 0L) {
                return 0;
            }
            return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
        }
    }
}
