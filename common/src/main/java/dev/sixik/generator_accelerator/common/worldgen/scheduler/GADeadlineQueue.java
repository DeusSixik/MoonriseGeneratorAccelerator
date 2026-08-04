package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.util.PriorityQueue;

public final class GADeadlineQueue {
    private final PriorityQueue<Entry> queue = new PriorityQueue<>((left, right) -> Long.compare(left.deadlineNanos, right.deadlineNanos));

    public void offer(long handle, long deadlineNanos) {
        if (handle == GAWorkHandle.NULL_HANDLE) {
            return;
        }
        synchronized (queue) {
            queue.offer(new Entry(handle, deadlineNanos));
        }
    }

    public long pollDue(long nowNanos) {
        synchronized (queue) {
            Entry entry = queue.peek();
            if (entry == null || entry.deadlineNanos > nowNanos) {
                return GAWorkHandle.NULL_HANDLE;
            }
            queue.poll();
            return entry.handle;
        }
    }

    public int size() {
        synchronized (queue) {
            return queue.size();
        }
    }

    private record Entry(long handle, long deadlineNanos) {
    }
}
