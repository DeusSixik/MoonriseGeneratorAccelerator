package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class GAChunkGraphArena {
    private static final VarHandle PENDING_DEPS = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle FLAGS = MethodHandles.arrayElementVarHandle(byte[].class);

    private static final byte STATE_READY = 0;
    private static final byte STATE_RUNNING = 1;
    private static final byte STATE_WAITING = 2;
    private static final byte STATE_COMPLETE = 3;
    private static final byte STATE_FAILED = 4;
    private static final byte STATE_CANCELLED = 5;
    private static final byte STATE_RESUME_PENDING = 6;

    private final GASchedulerRuntime runtime;
    private final int arenaIndex;
    private final int capacity;
    private final int edgeCapacity;

    private final int[] pendingDeps;
    private final int[] firstDependent;
    private final int[] nextDependentEdge;
    private final int[] edgeToNode;
    private final byte[] statusId;
    private final byte[] taskClass;
    private final byte[] flags;
    private final int[] ownerWorker;
    private final long[] slotGeneration;
    private final int[] holderIndex;
    private final int[] chunkX;
    private final int[] chunkZ;
    private final long[] deadlineOrAge;
    private final GAChunkWorkKey[] keys;
    private final NodeBody[] bodies;

    private final AtomicInteger nextNode = new AtomicInteger();
    private final AtomicInteger nextEdge = new AtomicInteger();
    private final AtomicInteger activeNodes = new AtomicInteger();
    private volatile boolean retired;

    public GAChunkGraphArena(GASchedulerRuntime runtime, int arenaIndex, int capacity, int edgeCapacity) {
        this.runtime = runtime;
        this.arenaIndex = arenaIndex;
        this.capacity = Math.max(1, Math.min(GAWorkHandle.MAX_NODE_INDEX + 1, capacity));
        this.edgeCapacity = Math.max(1, edgeCapacity);
        this.pendingDeps = new int[this.capacity];
        this.firstDependent = new int[this.capacity];
        this.nextDependentEdge = new int[this.edgeCapacity];
        this.edgeToNode = new int[this.edgeCapacity];
        this.statusId = new byte[this.capacity];
        this.taskClass = new byte[this.capacity];
        this.flags = new byte[this.capacity];
        this.ownerWorker = new int[this.capacity];
        this.slotGeneration = new long[this.capacity];
        this.holderIndex = new int[this.capacity];
        this.chunkX = new int[this.capacity];
        this.chunkZ = new int[this.capacity];
        this.deadlineOrAge = new long[this.capacity];
        this.keys = new GAChunkWorkKey[this.capacity];
        this.bodies = new NodeBody[this.capacity];
        Arrays.fill(this.firstDependent, -1);
    }

    public int arenaIndex() {
        return arenaIndex;
    }

    public int capacity() {
        return capacity;
    }

    public boolean retired() {
        return retired;
    }

    public int allocatedNodes() {
        return nextNode.get();
    }

    public int allocatedEdges() {
        return nextEdge.get();
    }

    public int activeNodes() {
        return activeNodes.get();
    }

    public long estimatedBytes() {
        return (long) capacity * (Integer.BYTES * 5L + Long.BYTES * 2L + 3L)
                + (long) edgeCapacity * (Integer.BYTES * 2L);
    }

    public boolean reusable() {
        return activeNodes.get() == 0 && nextNode.get() >= capacity;
    }

    public synchronized void resetForReuse() {
        if (activeNodes.get() != 0) {
            return;
        }
        nextNode.set(0);
        nextEdge.set(0);
        retired = false;
    }

    public long allocateNode(GAChunkWorkKey key, GATaskClass taskClass, int ownerWorker, NodeBody body) {
        int nodeIndex = nextNode.getAndIncrement();
        if (nodeIndex < 0 || nodeIndex >= capacity) {
            nextNode.decrementAndGet();
            throw new IllegalStateException("GA scheduler arena node cap exceeded: " + capacity);
        }
        long generation = (slotGeneration[nodeIndex] + 1L) & 0xFFFF_FFFFL;
        if (generation == 0L) {
            generation = 1L;
        }
        slotGeneration[nodeIndex] = generation;
        pendingDeps[nodeIndex] = 0;
        firstDependent[nodeIndex] = -1;
        statusId[nodeIndex] = key == null ? GAChunkWorkKey.STATUS_LEGACY_BOUNDARY : key.statusId();
        this.taskClass[nodeIndex] = (byte) taskClass.ordinal();
        this.flags[nodeIndex] = STATE_READY;
        this.ownerWorker[nodeIndex] = Math.max(0, ownerWorker);
        holderIndex[nodeIndex] = key == null ? 0 : key.hashCode();
        chunkX[nodeIndex] = key == null ? 0 : key.chunkX();
        chunkZ[nodeIndex] = key == null ? 0 : key.chunkZ();
        deadlineOrAge[nodeIndex] = System.nanoTime();
        keys[nodeIndex] = key;
        bodies[nodeIndex] = body;
        activeNodes.incrementAndGet();
        return GAWorkHandle.encode(generation, arenaIndex, nodeIndex, 0);
    }

    public void addDependency(long parentHandle, long childHandle) {
        int parent = validateLocalNode(parentHandle);
        int child = validateLocalNode(childHandle);
        int edge = nextEdge.getAndIncrement();
        if (edge < 0 || edge >= edgeCapacity) {
            nextEdge.decrementAndGet();
            throw new IllegalStateException("GA scheduler arena edge cap exceeded: " + edgeCapacity);
        }
        edgeToNode[edge] = child;
        nextDependentEdge[edge] = firstDependent[parent];
        firstDependent[parent] = edge;
        PENDING_DEPS.getAndAdd(pendingDeps, child, 1);
        runtime.metrics().addReadDependencyEdges(1L);
    }

    public int publishRoots() {
        int published = 0;
        int count = nextNode.get();
        for (int i = 0; i < count; i++) {
            if (pendingDeps[i] == 0 && flags[i] == STATE_READY) {
                runtime.publish(handleForNode(i), ownerWorker[i], taskClass(i), false);
                published++;
            }
        }
        return published;
    }

    public boolean publishIfReadyRoot(long handle) {
        int node = GAWorkHandle.nodeIndex(handle);
        if (!validateHandle(handle, node)) {
            runtime.metrics().recordStaleHandle();
            return false;
        }
        if (pendingDeps[node] == 0 && flags[node] == STATE_READY) {
            runtime.publish(handle, ownerWorker[node], taskClass(node), GAWorkHandle.urgent(handle));
            return true;
        }
        return false;
    }

    public void markQueued(long handle, long nowNanos) {
        int node = GAWorkHandle.nodeIndex(handle);
        if (node >= 0 && node < capacity) {
            deadlineOrAge[node] = nowNanos;
        }
    }

    public GATaskClass taskClass(int nodeIndex) {
        int ordinal = Byte.toUnsignedInt(taskClass[nodeIndex]);
        GATaskClass[] values = GATaskClass.values();
        return ordinal >= values.length ? GATaskClass.BOUNDARY : values[ordinal];
    }

    public GATaskClass taskClassForHandle(long handle) {
        int node = GAWorkHandle.nodeIndex(handle);
        if (node < 0 || node >= capacity) {
            return GATaskClass.BOUNDARY;
        }
        return taskClass(node);
    }

    public int ownerForHandle(long handle) {
        int node = GAWorkHandle.nodeIndex(handle);
        if (node < 0 || node >= capacity) {
            return 0;
        }
        return ownerWorker[node];
    }

    public boolean resume(long handle) {
        int node = GAWorkHandle.nodeIndex(handle);
        if (!validateHandle(handle, node)) {
            runtime.metrics().recordStaleHandle();
            return false;
        }
        for (;;) {
            byte state = (byte) FLAGS.getAcquire(flags, node);
            if (state == STATE_WAITING) {
                if (FLAGS.compareAndSet(flags, node, STATE_WAITING, STATE_READY)) {
                    publishResume(handle, node);
                    return true;
                }
                continue;
            }
            if (state == STATE_RUNNING) {
                if (FLAGS.compareAndSet(flags, node, STATE_RUNNING, STATE_RESUME_PENDING)) {
                    return true;
                }
                continue;
            }
            if (state == STATE_READY || state == STATE_RESUME_PENDING) {
                return true;
            }
            if (state == STATE_COMPLETE || state == STATE_CANCELLED || state == STATE_FAILED) {
                runtime.metrics().recordLateExternalCompletion();
            } else {
                runtime.metrics().recordCancelledResumeHandle();
            }
            return false;
        }
    }

    private void publishResume(long handle, int node) {
        runtime.metrics().recordResumeQueuePublish();
        runtime.publish(GAWorkHandle.withFlags(handle, GAWorkHandle.flags(handle) | GAWorkHandle.FLAG_RESUME | GAWorkHandle.FLAG_URGENT),
                ownerWorker[node], taskClass(node), true);
    }

    public void runHandle(GAWorker worker, long handle) {
        int node = GAWorkHandle.nodeIndex(handle);
        if (!validateHandle(handle, node)) {
            runtime.metrics().recordStaleHandle();
            return;
        }
        if (!FLAGS.compareAndSet(flags, node, STATE_READY, STATE_RUNNING)) {
            byte state = (byte) FLAGS.getAcquire(flags, node);
            if (state == STATE_WAITING) {
                runtime.metrics().recordCancelledResumeHandle();
            }
            return;
        }

        GATaskClass klass = taskClass(node);
        long queuedAt = deadlineOrAge[node];
        if (queuedAt > 0L) {
            runtime.metrics().addQueueWait(klass, System.nanoTime() - queuedAt);
        }
        runtime.metrics().recordStart(klass);
        long start = System.nanoTime();
        RunState state;
        try {
            NodeBody body = bodies[node];
            if (body == null) {
                throw new IllegalStateException("GA scheduler node has no body: arena=" + arenaIndex + " node=" + node);
            }
            state = body.run(new ExecutionContext(runtime, worker, this, node, handle, keys[node]));
        } catch (Throwable throwable) {
            failNode(node, klass, start, throwable);
            return;
        }

        if (state == RunState.WAITING) {
            byte currentState = (byte) FLAGS.getAcquire(flags, node);
            if (currentState == STATE_RESUME_PENDING) {
                FLAGS.setRelease(flags, node, STATE_READY);
                publishResume(handle, node);
            } else {
                FLAGS.setRelease(flags, node, STATE_WAITING);
            }
            runtime.metrics().decrementInFlightStatus();
            runtime.metrics().recordCompletion(klass, System.nanoTime() - start);
            return;
        }
        if (state == RunState.DEFERRED) {
            FLAGS.setRelease(flags, node, STATE_READY);
            runtime.metrics().decrementInFlightStatus();
            runtime.metrics().recordCompletion(klass, System.nanoTime() - start);
            return;
        }

        FLAGS.setRelease(flags, node, STATE_COMPLETE);
        bodies[node] = null;
        keys[node] = null;
        runtime.metrics().recordCompletion(klass, System.nanoTime() - start);
        activeNodes.decrementAndGet();
        completeDependents(node);
    }

    public void cancelAll() {
        int count = nextNode.get();
        for (int i = 0; i < count; i++) {
            byte state = (byte) FLAGS.getAcquire(flags, i);
            if (state != STATE_COMPLETE && state != STATE_FAILED && state != STATE_CANCELLED) {
                FLAGS.setRelease(flags, i, STATE_CANCELLED);
                bodies[i] = null;
                keys[i] = null;
                activeNodes.updateAndGet(value -> Math.max(0, value - 1));
                runtime.metrics().recordCancelled(taskClass(i));
            }
        }
    }

    private int validateLocalNode(long handle) {
        if (GAWorkHandle.arenaIndex(handle) != arenaIndex) {
            throw new IllegalArgumentException("dependency crosses arenas");
        }
        int node = GAWorkHandle.nodeIndex(handle);
        if (!validateHandle(handle, node)) {
            throw new IllegalArgumentException("invalid or stale handle: " + handle);
        }
        return node;
    }

    private boolean validateHandle(long handle, int node) {
        if (retired || node < 0 || node >= capacity) {
            return false;
        }
        return (int) slotGeneration[node] == GAWorkHandle.generationLow32(handle);
    }

    private long handleForNode(int nodeIndex) {
        return GAWorkHandle.encode(slotGeneration[nodeIndex], arenaIndex, nodeIndex, 0);
    }

    private void completeDependents(int node) {
        int edge = firstDependent[node];
        while (edge >= 0) {
            int dependent = edgeToNode[edge];
            int previous = (int) PENDING_DEPS.getAndAdd(pendingDeps, dependent, -1);
            if (previous == 1) {
                long handle = handleForNode(dependent);
                int owner = ownerWorker[dependent];
                if (runtime.currentWorkerIndex() == owner) {
                    runtime.metrics().recordSameWorkerContinuation();
                }
                runtime.publish(handle, owner, taskClass(dependent), false);
            }
            edge = nextDependentEdge[edge];
        }
    }

    private void failNode(int node, GATaskClass klass, long start, Throwable throwable) {
        FLAGS.setRelease(flags, node, STATE_FAILED);
        bodies[node] = null;
        keys[node] = null;
        runtime.metrics().recordFailure(klass, System.nanoTime() - start);
        activeNodes.decrementAndGet();
        runtime.handleNodeFailure(throwable);
    }

    @FunctionalInterface
    public interface NodeBody {
        RunState run(ExecutionContext context) throws Exception;
    }

    public enum RunState {
        COMPLETE,
        WAITING,
        DEFERRED
    }

    public static final class ExecutionContext {
        private final GASchedulerRuntime runtime;
        private final GAWorker worker;
        private final GAChunkGraphArena arena;
        private final int nodeIndex;
        private final long handle;
        private final GAChunkWorkKey key;

        private ExecutionContext(
                GASchedulerRuntime runtime,
                GAWorker worker,
                GAChunkGraphArena arena,
                int nodeIndex,
                long handle,
                GAChunkWorkKey key
        ) {
            this.runtime = runtime;
            this.worker = worker;
            this.arena = arena;
            this.nodeIndex = nodeIndex;
            this.handle = handle;
            this.key = key;
        }

        public GASchedulerRuntime runtime() {
            return runtime;
        }

        public GAWorker worker() {
            return worker;
        }

        public GAChunkGraphArena arena() {
            return arena;
        }

        public int nodeIndex() {
            return nodeIndex;
        }

        public long handle() {
            return handle;
        }

        public GAChunkWorkKey key() {
            return key;
        }

        public void resume() {
            arena.resume(handle);
        }

        public void deferNanos(long nanos) {
            runtime.defer(worker, handle, nanos);
        }
    }
}
