package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import dev.sixik.generator_accelerator.GeneratorAccelerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class GASchedulerRuntime {
    private final GAWorkerConfig config;
    private final GAMetrics metrics = new GAMetrics();
    private final GAWorkerTopology topology;
    private final GAWorker[] workers;
    private final GAStealPolicy stealPolicy;
    private final GAChunkGraphArena[] arenas;
    private final GADeadlineQueue[] deadlines;
    private final GAChunkWorkTable workTable;
    private final AtomicInteger nextArena = new AtomicInteger();
    private final AtomicInteger roundRobinOwner = new AtomicInteger();
    private final ThreadLocal<GAWorker> currentWorker = new ThreadLocal<>();
    private final AtomicReference<String> admissionDisabledReason = new AtomicReference<>();
    private volatile boolean shutdownRequested;

    public GASchedulerRuntime(GAWorkerConfig config) {
        this.config = config;
        this.topology = new GAWorkerTopology(config.workers());
        this.workers = new GAWorker[config.workers()];
        this.deadlines = new GADeadlineQueue[config.workers()];
        int perWorkerQueue = Math.max(2, config.maxQueuedHandlesPerWorker());
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new GAWorker(this, i, perWorkerQueue, config.maxParkNanos());
            deadlines[i] = new GADeadlineQueue();
        }
        this.stealPolicy = new GAStealPolicy(workers.length);
        this.arenas = new GAChunkGraphArena[Math.max(1, Math.min(256, config.maxArenas()))];
        this.workTable = new GAChunkWorkTable(config.maxExternalWaiters(), metrics);
    }

    public GAWorkerConfig config() {
        return config;
    }

    public GAMetrics metrics() {
        return metrics;
    }

    public GAWorkerTopology topology() {
        return topology;
    }

    public GAStealPolicy stealPolicy() {
        return stealPolicy;
    }

    public GAChunkWorkTable workTable() {
        return workTable;
    }

    public int workerCount() {
        return workers.length;
    }

    public GAWorker worker(int index) {
        return workers[index];
    }

    public boolean shutdownRequested() {
        return shutdownRequested;
    }

    public void start() {
        for (GAWorker worker : workers) {
            worker.start();
        }
    }

    public void shutdown(boolean cancelWork) {
        shutdownRequested = true;
        if (cancelWork) {
            cancelAll(new RejectedExecutionException("GA v2 scheduler is shutting down"));
        }
        for (GAWorker worker : workers) {
            worker.shutdown();
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MICROSECONDS.toNanos(config.rollbackDrainTimeoutMicros());
        for (GAWorker worker : workers) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                worker.join(Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void cancelAll(Throwable cause) {
        workTable.cancelAll(cause);
        for (GAChunkGraphArena arena : arenas) {
            if (arena != null) {
                arena.cancelAll();
            }
        }
    }

    public <T> CompletableFuture<T> submit(
            GATaskClass taskClass,
            GAChunkWorkKey key,
            Supplier<T> supplier,
            boolean urgent
    ) {
        if (!admissionOpen()) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RejectedExecutionException("GA v2 scheduler admission closed: " + admissionState()));
            return failed;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            GAChunkGraphArena arena = acquireSharedArena(config.batchMaxNodes(), config.batchMaxEdges());
            int owner = key == null ? nextRoundRobinOwner() : topology.owner(key);
            long handle = arena.allocateNode(key, taskClass, owner, context -> {
                if (future.isCancelled()) {
                    return GAChunkGraphArena.RunState.COMPLETE;
                }
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                    throw throwable;
                }
                return GAChunkGraphArena.RunState.COMPLETE;
            });
            metrics.recordSubmitted(taskClass);
            if (urgent) {
                handle = GAWorkHandle.withFlags(handle, GAWorkHandle.flags(handle) | GAWorkHandle.FLAG_URGENT);
            }
            arena.publishIfReadyRoot(handle);
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    public void execute(
            GATaskClass taskClass,
            GAChunkWorkKey key,
            Runnable runnable,
            Consumer<Throwable> failureHandler,
            boolean urgent
    ) {
        CompletableFuture<Void> future = submit(taskClass, key, () -> {
            runnable.run();
            return null;
        }, urgent);
        future.whenComplete((ignored, failure) -> {
            if (failure != null && failureHandler != null) {
                failureHandler.accept(unwrapCompletion(failure));
            } else if (failure != null) {
                GeneratorAccelerator.LOGGER.warn("GA v2 scheduler async task failed", unwrapCompletion(failure));
            }
        });
    }

    public GAChunkGraphArena acquireArena(int capacity, int edgeCapacity) {
        int start = Math.floorMod(nextArena.getAndIncrement(), arenas.length);
        for (int scanned = 0; scanned < arenas.length; scanned++) {
            int index = (start + scanned) % arenas.length;
            GAChunkGraphArena existing = arenas[index];
            if (existing == null) {
                GAChunkGraphArena created = new GAChunkGraphArena(this, index, capacity, edgeCapacity);
                arenas[index] = created;
                updateArenaBytes();
                return created;
            }
            if (existing.activeNodes() == 0 && existing.capacity() >= capacity) {
                existing.resetForReuse();
                return existing;
            }
        }
        metrics.recordFallback(GAMetrics.FallbackReason.ARENA_CAP);
        throw new RejectedExecutionException("GA v2 scheduler arena cap reached: " + arenas.length);
    }

    GAChunkGraphArena acquireSharedArena(int capacity, int edgeCapacity) {
        int start = Math.floorMod(nextArena.getAndIncrement(), arenas.length);
        for (int scanned = 0; scanned < arenas.length; scanned++) {
            int index = (start + scanned) % arenas.length;
            GAChunkGraphArena existing = arenas[index];
            if (existing == null) {
                GAChunkGraphArena created = new GAChunkGraphArena(this, index, capacity, edgeCapacity);
                arenas[index] = created;
                updateArenaBytes();
                return created;
            }
            if (existing.allocatedNodes() < existing.capacity()) {
                return existing;
            }
            if (existing.activeNodes() == 0 && existing.capacity() >= capacity) {
                existing.resetForReuse();
                return existing;
            }
        }
        metrics.recordFallback(GAMetrics.FallbackReason.ARENA_CAP);
        throw new RejectedExecutionException("GA v2 scheduler shared arena cap reached: " + arenas.length);
    }

    public void publish(long handle, int ownerWorker, GATaskClass taskClass, boolean urgent) {
        long overheadStart = System.nanoTime();
        int owner = Math.floorMod(ownerWorker, workers.length);
        GAWorker current = currentWorker.get();
        GAChunkGraphArena arena = arenaForHandle(handle);
        if (arena != null) {
            arena.markQueued(handle, System.nanoTime());
        }
        boolean accepted;
        if (current != null && current.index() == owner) {
            accepted = current.offerLocal(taskClass, handle, urgent || GAWorkHandle.urgent(handle));
        } else {
            accepted = workers[owner].offerInbox(handle, urgent || GAWorkHandle.resume(handle) || GAWorkHandle.urgent(handle));
        }
        metrics.addSchedulerOverhead(taskClass, System.nanoTime() - overheadStart);
        if (!accepted) {
            metrics.recordDroppedHandle();
            disableAdmission("queue full while publishing handle");
            throw new RejectedExecutionException("GA v2 scheduler queue full for worker " + owner);
        }
    }

    public void defer(GAWorker worker, long handle, long nanos) {
        int owner = worker == null ? ownerForHandle(handle) : worker.index();
        deadlines[Math.floorMod(owner, deadlines.length)].offer(handle, System.nanoTime() + Math.max(0L, nanos));
    }

    long pollDue(GAWorker worker) {
        return deadlines[worker.index()].pollDue(System.nanoTime());
    }

    public void runHandle(GAWorker worker, long handle) {
        GAChunkGraphArena arena = arenaForHandle(handle);
        if (arena == null) {
            metrics.recordInvalidHandle();
            return;
        }
        arena.runHandle(worker, handle);
    }

    public GATaskClass taskClassForHandle(long handle) {
        GAChunkGraphArena arena = arenaForHandle(handle);
        return arena == null ? GATaskClass.BOUNDARY : arena.taskClassForHandle(handle);
    }

    public int ownerForHandle(long handle) {
        GAChunkGraphArena arena = arenaForHandle(handle);
        return arena == null ? nextRoundRobinOwner() : arena.ownerForHandle(handle);
    }

    public int currentWorkerIndex() {
        GAWorker worker = currentWorker.get();
        return worker == null ? -1 : worker.index();
    }

    public boolean isCurrentWorker() {
        return currentWorker.get() != null;
    }

    public int nextRoundRobinOwner() {
        return Math.floorMod(roundRobinOwner.getAndIncrement(), workers.length);
    }

    public int activeWorkerEstimate() {
        int active = 0;
        for (GAWorker worker : workers) {
            if (worker.activeDepth() > 0) {
                active++;
            }
        }
        return active;
    }

    public void bindWorker(GAWorker worker) {
        currentWorker.set(worker);
    }

    public void unbindWorker() {
        currentWorker.remove();
    }

    public void disableAdmission(String reason) {
        admissionDisabledReason.compareAndSet(null, reason);
    }

    public boolean admissionOpen() {
        return !shutdownRequested && admissionDisabledReason.get() == null;
    }

    public String admissionState() {
        if (shutdownRequested) {
            return "shutdown";
        }
        String reason = admissionDisabledReason.get();
        return reason == null ? "open" : "disabled:" + reason;
    }

    public void handleNodeFailure(Throwable throwable) {
        disableAdmission("node failure");
        GeneratorAccelerator.LOGGER.warn("GA v2 scheduler node failed", throwable);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = metrics.snapshot(config, admissionState(), this);
        ArrayList<Map<String, Object>> workerSnapshots = new ArrayList<>(workers.length);
        for (GAWorker worker : workers) {
            workerSnapshots.add(worker.snapshot());
        }
        out.put("workerQueues", workerSnapshots);
        out.put("workTableInFlight", workTable.inFlight());
        out.put("arenaCount", arenaCount());
        out.put("activeArenaNodes", activeArenaNodes());
        return out;
    }

    public void resetMetrics() {
        metrics.reset();
    }

    private GAChunkGraphArena arenaForHandle(long handle) {
        int index = GAWorkHandle.arenaIndex(handle);
        if (index < 0 || index >= arenas.length) {
            return null;
        }
        return arenas[index];
    }

    private int arenaCount() {
        int count = 0;
        for (GAChunkGraphArena arena : arenas) {
            if (arena != null) {
                count++;
            }
        }
        return count;
    }

    private int activeArenaNodes() {
        int active = 0;
        for (GAChunkGraphArena arena : arenas) {
            if (arena != null) {
                active += arena.activeNodes();
            }
        }
        return active;
    }

    private void updateArenaBytes() {
        long bytes = 0L;
        for (GAChunkGraphArena arena : arenas) {
            if (arena != null) {
                bytes += arena.estimatedBytes();
            }
        }
        metrics.setArenaBytesRetained(bytes);
        if (bytes + config.estimatedQueueBytes() > config.schedulerMaxMemoryBytes()) {
            metrics.recordFallback(GAMetrics.FallbackReason.PRESSURE);
            disableAdmission("scheduler memory cap exceeded");
        }
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }
}
