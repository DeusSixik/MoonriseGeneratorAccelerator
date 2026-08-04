package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.util.ArrayList;
import java.util.List;

public final class GAGenerationBatch {
    private final GASchedulerRuntime runtime;
    private final GAChunkGraphArena arena;
    private final ArrayList<Long> handles;
    private final int maxCenters;
    private int centers;
    private boolean submitted;

    private GAGenerationBatch(GASchedulerRuntime runtime, GAChunkGraphArena arena, int maxCenters) {
        this.runtime = runtime;
        this.arena = arena;
        this.maxCenters = Math.max(1, maxCenters);
        this.handles = new ArrayList<>();
    }

    public static GAGenerationBatch create(GASchedulerRuntime runtime) {
        GAWorkerConfig config = runtime.config();
        return new GAGenerationBatch(
                runtime,
                runtime.acquireSharedArena(config.batchMaxNodes(), config.batchMaxEdges()),
                config.batchMaxCenters()
        );
    }

    public GAChunkGraphArena arena() {
        return arena;
    }

    public int centers() {
        return centers;
    }

    public int nodeCount() {
        return handles.size();
    }

    public List<Long> handles() {
        return List.copyOf(handles);
    }

    public boolean canAcceptCenter() {
        return centers < maxCenters;
    }

    public void addCenter() {
        if (!canAcceptCenter()) {
            throw new IllegalStateException("GA scheduler batch center cap exceeded: " + maxCenters);
        }
        centers++;
    }
    public long addNode(GAChunkWorkKey key, GATaskClass taskClass, GAChunkGraphArena.NodeBody body) {
        int owner = key == null ? runtime.nextRoundRobinOwner() : runtime.topology().owner(key);
        long handle = arena.allocateNode(key, taskClass, owner, body);
        handles.add(handle);
        runtime.metrics().recordSubmitted(taskClass);
        return handle;
    }

    public void addDependency(long parentHandle, long childHandle) {
        arena.addDependency(parentHandle, childHandle);
    }

    public int submit() {
        if (submitted) {
            return 0;
        }
        submitted = true;
        int published = 0;
        for (long handle : handles) {
            if (arena.publishIfReadyRoot(handle)) {
                published++;
            }
        }
        return published;
    }
}
