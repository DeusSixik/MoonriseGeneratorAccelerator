package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class GAChunkWorkTable {
    private final ConcurrentHashMap<GAChunkWorkKey, Entry> entries = new ConcurrentHashMap<>();
    private final int maxExternalWaiters;
    private final GAMetrics metrics;

    public GAChunkWorkTable(int maxExternalWaiters, GAMetrics metrics) {
        this.maxExternalWaiters = Math.max(1, maxExternalWaiters);
        this.metrics = metrics;
    }

    public <T> CompletableFuture<T> coalesce(GAChunkWorkKey key, Supplier<CompletableFuture<T>> starter) {
        Entry created = new Entry(key);
        Entry existing = entries.putIfAbsent(key, created);
        if (existing != null) {
            metrics.recordDuplicateJoin();
            return existing.attach();
        }

        CompletableFuture<T> publicFuture = created.attach();
        created.transition(State.CLAIMED, State.RUNNING);
        CompletableFuture<T> started;
        try {
            started = requireFuture(starter.get());
        } catch (Throwable throwable) {
            created.complete(null, throwable);
            entries.remove(key, created);
            return publicFuture;
        }

        started.whenComplete((result, failure) -> {
            created.complete(result, failure);
            entries.remove(key, created);
        });
        return publicFuture;
    }

    public int inFlight() {
        return entries.size();
    }

    public void cancelAll(Throwable cause) {
        for (Entry entry : entries.values()) {
            entry.cancel(cause);
            entries.remove(entry.key, entry);
        }
    }

    private static <T> CompletableFuture<T> requireFuture(CompletableFuture<T> future) {
        if (future == null) {
            throw new NullPointerException("coalesced work returned null future");
        }
        return future;
    }

    private final class Entry {
        private final GAChunkWorkKey key;
        private final AtomicInteger state = new AtomicInteger(State.CLAIMED.ordinal());
        private final ArrayList<CompletableFuture<Object>> waiters = new ArrayList<>(2);
        private Object completedResult;
        private Throwable completedFailure;

        private Entry(GAChunkWorkKey key) {
            this.key = key;
        }

        @SuppressWarnings("unchecked")
        private <T> CompletableFuture<T> attach() {
            synchronized (this) {
                int currentState = state.get();
                if (currentState == State.COMPLETE.ordinal()) {
                    return CompletableFuture.completedFuture((T) completedResult);
                }
                if (currentState == State.FAILED.ordinal() || currentState == State.CANCELLED.ordinal()) {
                    CompletableFuture<T> completed = new CompletableFuture<>();
                    completed.completeExceptionally(completedFailure == null
                            ? new IllegalStateException("GA scheduler work completed without a failure cause")
                            : completedFailure);
                    return completed;
                }
                if (waiters.size() >= maxExternalWaiters) {
                    CompletableFuture<T> rejected = new CompletableFuture<>();
                    rejected.completeExceptionally(new IllegalStateException("GA scheduler external waiter cap reached"));
                    return rejected;
                }
                CompletableFuture<Object> waiter = new CompletableFuture<>();
                waiters.add(waiter);
                return (CompletableFuture<T>) (CompletableFuture<?>) waiter;
            }
        }

        private void transition(State from, State to) {
            state.compareAndSet(from.ordinal(), to.ordinal());
        }

        private void complete(Object result, Throwable failure) {
            List<CompletableFuture<Object>> listeners;
            synchronized (this) {
                completedResult = result;
                completedFailure = failure;
                state.set((failure == null ? State.COMPLETE : State.FAILED).ordinal());
                listeners = drainWaitersLocked();
            }
            for (CompletableFuture<Object> waiter : listeners) {
                boolean delivered = failure == null ? waiter.complete(result) : waiter.completeExceptionally(failure);
                if (!delivered) {
                    metrics.recordPublicFutureCompletionFailure();
                }
            }
        }

        private void cancel(Throwable cause) {
            List<CompletableFuture<Object>> listeners;
            synchronized (this) {
                completedFailure = cause;
                state.set(State.CANCELLED.ordinal());
                listeners = drainWaitersLocked();
            }
            for (CompletableFuture<Object> waiter : listeners) {
                boolean delivered = waiter.completeExceptionally(cause);
                if (!delivered) {
                    metrics.recordPublicFutureCompletionFailure();
                }
            }
        }

        private List<CompletableFuture<Object>> drainWaitersLocked() {
            ArrayList<CompletableFuture<Object>> listeners = new ArrayList<>(waiters);
            waiters.clear();
            return listeners;
        }
    }

    public enum State {
        ABSENT,
        CLAIMED,
        RUNNING,
        COMPLETING,
        COMPLETE,
        FAILED,
        CANCELLED
    }
}
