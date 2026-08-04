package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class GAMetrics {
    private final AtomicLongArray submitted = new AtomicLongArray(GATaskClass.values().length);
    private final AtomicLongArray completed = new AtomicLongArray(GATaskClass.values().length);
    private final AtomicLongArray failed = new AtomicLongArray(GATaskClass.values().length);
    private final AtomicLongArray active = new AtomicLongArray(GATaskClass.values().length);
    private final AtomicLongArray maxActive = new AtomicLongArray(GATaskClass.values().length);
    private final AtomicLongArray usefulCpuNanos = new AtomicLongArray(GATaskClass.values().length);
    private final AtomicLongArray schedulerOverheadNanos = new AtomicLongArray(GATaskClass.values().length);
    private final AtomicLongArray queueWaitNanos = new AtomicLongArray(GATaskClass.values().length);

    private final AtomicLong workerIdleNanos = new AtomicLong();
    private final AtomicLong blockedWorkerNanos = new AtomicLong();
    private final AtomicLong batchBuildNanos = new AtomicLong();
    private final AtomicLong arenaBytesRetained = new AtomicLong();
    private final AtomicLong inFlightStatusCount = new AtomicLong();
    private final AtomicLong externalFutureWaitCount = new AtomicLong();
    private final AtomicLong staleHandlesDiscarded = new AtomicLong();
    private final AtomicLong droppedHandles = new AtomicLong();
    private final AtomicLong invalidHandleExecutions = new AtomicLong();
    private final AtomicLong workerFatalErrors = new AtomicLong();
    private final AtomicLong nodeFailures = new AtomicLong();
    private final AtomicLong nodeCancellations = new AtomicLong();
    private final AtomicLong lateExternalCompletions = new AtomicLong();
    private final AtomicLong cancelledResumeHandles = new AtomicLong();
    private final AtomicLong publicFutureCompletionFailures = new AtomicLong();
    private final AtomicLong duplicateWorkJoined = new AtomicLong();
    private final AtomicLong batchLocalDedupHits = new AtomicLong();
    private final AtomicLong readDependencyEdges = new AtomicLong();
    private final AtomicLong resumeQueuePublishes = new AtomicLong();
    private final AtomicLong sameWorkerContinuations = new AtomicLong();
    private final AtomicLong stealAttempts = new AtomicLong();
    private final AtomicLong stealSuccesses = new AtomicLong();
    private final AtomicLong fallbackToVanilla = new AtomicLong();
    private final EnumMap<FallbackReason, AtomicLong> fallbackReasons = new EnumMap<>(FallbackReason.class);

    public GAMetrics() {
        for (FallbackReason reason : FallbackReason.values()) {
            fallbackReasons.put(reason, new AtomicLong());
        }
    }

    public void recordSubmitted(GATaskClass taskClass) {
        submitted.incrementAndGet(taskClass.ordinal());
    }

    public void recordStart(GATaskClass taskClass) {
        int index = taskClass.ordinal();
        long now = active.incrementAndGet(index);
        updateMax(maxActive, index, now);
    }

    public void recordCompletion(GATaskClass taskClass, long nanos) {
        int index = taskClass.ordinal();
        completed.incrementAndGet(index);
        usefulCpuNanos.addAndGet(index, Math.max(0L, nanos));
        active.decrementAndGet(index);
    }

    public void recordFailure(GATaskClass taskClass, long nanos) {
        int index = taskClass.ordinal();
        failed.incrementAndGet(index);
        usefulCpuNanos.addAndGet(index, Math.max(0L, nanos));
        active.decrementAndGet(index);
        nodeFailures.incrementAndGet();
    }

    public void recordCancelled(GATaskClass taskClass) {
        failed.incrementAndGet(taskClass.ordinal());
        nodeCancellations.incrementAndGet();
    }

    public void addSchedulerOverhead(GATaskClass taskClass, long nanos) {
        schedulerOverheadNanos.addAndGet(taskClass.ordinal(), Math.max(0L, nanos));
    }

    public void addQueueWait(GATaskClass taskClass, long nanos) {
        queueWaitNanos.addAndGet(taskClass.ordinal(), Math.max(0L, nanos));
    }

    public void addIdleNanos(long nanos) {
        workerIdleNanos.addAndGet(Math.max(0L, nanos));
    }

    public void addBlockedWorkerNanos(long nanos) {
        blockedWorkerNanos.addAndGet(Math.max(0L, nanos));
    }

    public void addBatchBuildNanos(long nanos) {
        batchBuildNanos.addAndGet(Math.max(0L, nanos));
    }

    public void setArenaBytesRetained(long bytes) {
        arenaBytesRetained.set(Math.max(0L, bytes));
    }

    public void incrementInFlightStatus() {
        inFlightStatusCount.incrementAndGet();
    }

    public void decrementInFlightStatus() {
        inFlightStatusCount.updateAndGet(value -> Math.max(0L, value - 1L));
    }

    public void incrementExternalFutureWait() {
        externalFutureWaitCount.incrementAndGet();
    }

    public void decrementExternalFutureWait() {
        externalFutureWaitCount.updateAndGet(value -> Math.max(0L, value - 1L));
    }

    public void recordStaleHandle() {
        staleHandlesDiscarded.incrementAndGet();
    }

    public void recordDroppedHandle() {
        droppedHandles.incrementAndGet();
    }

    public void recordInvalidHandle() {
        invalidHandleExecutions.incrementAndGet();
    }

    public void recordWorkerFatalError() {
        workerFatalErrors.incrementAndGet();
    }

    public void recordLateExternalCompletion() {
        lateExternalCompletions.incrementAndGet();
    }

    public void recordCancelledResumeHandle() {
        cancelledResumeHandles.incrementAndGet();
    }

    public void recordPublicFutureCompletionFailure() {
        publicFutureCompletionFailures.incrementAndGet();
    }

    public void recordDuplicateJoin() {
        duplicateWorkJoined.incrementAndGet();
    }

    public void recordBatchLocalDedupHit() {
        batchLocalDedupHits.incrementAndGet();
    }

    public void addReadDependencyEdges(long count) {
        readDependencyEdges.addAndGet(Math.max(0L, count));
    }

    public void recordResumeQueuePublish() {
        resumeQueuePublishes.incrementAndGet();
    }

    public void recordSameWorkerContinuation() {
        sameWorkerContinuations.incrementAndGet();
    }

    public void recordSteal(boolean success) {
        stealAttempts.incrementAndGet();
        if (success) {
            stealSuccesses.incrementAndGet();
        }
    }

    public void recordFallback(FallbackReason reason) {
        fallbackToVanilla.incrementAndGet();
        fallbackReasons.get(reason).incrementAndGet();
    }

    public long blockedWorkerNanos() {
        return blockedWorkerNanos.get();
    }

    public long staleHandlesDiscarded() {
        return staleHandlesDiscarded.get();
    }

    public long invalidHandleExecutions() {
        return invalidHandleExecutions.get();
    }

    public long workerFatalErrors() {
        return workerFatalErrors.get();
    }

    public long droppedHandles() {
        return droppedHandles.get();
    }

    public Map<String, Object> snapshot(GAWorkerConfig config, String admissionState, GASchedulerRuntime runtime) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("mode", config.mode().name());
        out.put("workers", config.workers());
        out.put("admissionState", admissionState);
        out.put("config", config.toMap());
        out.put("taskClasses", taskClassSnapshot());
        out.put("throughput", throughputSnapshot());
        out.put("latency", latencySnapshot());
        out.put("utilization", utilizationSnapshot(runtime));
        out.put("overhead", overheadSnapshot());
        out.put("allocation", allocationSnapshot());
        out.put("memory", memorySnapshot(config));
        out.put("fallbackReasons", fallbackReasonSnapshot());
        out.put("ceilings", ceilingSnapshot());
        out.put("correctnessCounters", correctnessSnapshot());
        if (config.debugMetrics()) {
            out.put("debug", debugSnapshot());
        }
        return out;
    }

    public void reset() {
        for (int i = 0; i < GATaskClass.values().length; i++) {
            submitted.set(i, 0L);
            completed.set(i, 0L);
            failed.set(i, 0L);
            active.set(i, 0L);
            maxActive.set(i, 0L);
            usefulCpuNanos.set(i, 0L);
            schedulerOverheadNanos.set(i, 0L);
            queueWaitNanos.set(i, 0L);
        }
        workerIdleNanos.set(0L);
        blockedWorkerNanos.set(0L);
        batchBuildNanos.set(0L);
        arenaBytesRetained.set(0L);
        inFlightStatusCount.set(0L);
        externalFutureWaitCount.set(0L);
        staleHandlesDiscarded.set(0L);
        droppedHandles.set(0L);
        invalidHandleExecutions.set(0L);
        workerFatalErrors.set(0L);
        nodeFailures.set(0L);
        nodeCancellations.set(0L);
        lateExternalCompletions.set(0L);
        cancelledResumeHandles.set(0L);
        publicFutureCompletionFailures.set(0L);
        duplicateWorkJoined.set(0L);
        batchLocalDedupHits.set(0L);
        readDependencyEdges.set(0L);
        resumeQueuePublishes.set(0L);
        sameWorkerContinuations.set(0L);
        stealAttempts.set(0L);
        stealSuccesses.set(0L);
        fallbackToVanilla.set(0L);
        for (AtomicLong counter : fallbackReasons.values()) {
            counter.set(0L);
        }
    }

    private Map<String, Object> taskClassSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (GATaskClass taskClass : GATaskClass.values()) {
            int index = taskClass.ordinal();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("submitted", submitted.get(index));
            entry.put("completed", completed.get(index));
            entry.put("failed", failed.get(index));
            entry.put("active", active.get(index));
            entry.put("maxActive", maxActive.get(index));
            entry.put("usefulCpuNanos", usefulCpuNanos.get(index));
            entry.put("schedulerOverheadNanos", schedulerOverheadNanos.get(index));
            entry.put("queueWaitNanos", queueWaitNanos.get(index));
            out.put(taskClass.jsonName(), entry);
        }
        return out;
    }

    private Map<String, Object> throughputSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        long totalCompleted = 0L;
        for (int i = 0; i < GATaskClass.values().length; i++) {
            totalCompleted += completed.get(i);
        }
        out.put("completedTasks", totalCompleted);
        out.put("chunksPerSecond", 0.0D);
        return out;
    }

    private Map<String, Object> latencySnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queueWaitNanos", sum(queueWaitNanos));
        return out;
    }

    private Map<String, Object> utilizationSnapshot(GASchedulerRuntime runtime) {
        Map<String, Object> out = new LinkedHashMap<>();
        long useful = sum(usefulCpuNanos);
        long idle = workerIdleNanos.get();
        double utilization = useful + idle == 0L ? 0.0D : (double) useful * 100.0D / (double) (useful + idle);
        out.put("workerUtilizationPercent", utilization);
        out.put("usefulCpuNanos", useful);
        out.put("idleNanos", idle);
        out.put("activeWorkers", runtime.activeWorkerEstimate());
        return out;
    }

    private Map<String, Object> overheadSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schedulerOverheadNanos", sum(schedulerOverheadNanos));
        out.put("blockedWorkerNanos", blockedWorkerNanos.get());
        out.put("batchBuildNanos", batchBuildNanos.get());
        out.put("externalFutureWaitCount", externalFutureWaitCount.get());
        return out;
    }

    private Map<String, Object> allocationSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allocationRateBytesPerChunk", 0L);
        out.put("arenaBytesRetained", arenaBytesRetained.get());
        return out;
    }

    private Map<String, Object> memorySnapshot(GAWorkerConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arenaBytesRetained", arenaBytesRetained.get());
        out.put("schedulerEstimatedMemoryBytes", arenaBytesRetained.get() + config.estimatedQueueBytes());
        out.put("schedulerMaxMemoryBytes", config.schedulerMaxMemoryBytes());
        return out;
    }

    private Map<String, Object> fallbackReasonSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fallbackToVanilla", fallbackToVanilla.get());
        for (Map.Entry<FallbackReason, AtomicLong> entry : fallbackReasons.entrySet()) {
            out.put(entry.getKey().jsonName(), entry.getValue().get());
        }
        return out;
    }

    private Map<String, Object> ceilingSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commitCeiling", false);
        out.put("writerGuardCeiling", false);
        out.put("gpuDispatchCeiling", false);
        out.put("mailboxCeiling", false);
        return out;
    }

    private Map<String, Object> correctnessSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staleHandlesDiscarded", staleHandlesDiscarded.get());
        out.put("droppedHandles", droppedHandles.get());
        out.put("invalidHandleExecutions", invalidHandleExecutions.get());
        out.put("workerFatalErrors", workerFatalErrors.get());
        out.put("nodeFailures", nodeFailures.get());
        out.put("nodeCancellations", nodeCancellations.get());
        out.put("lateExternalCompletions", lateExternalCompletions.get());
        out.put("cancelledResumeHandles", cancelledResumeHandles.get());
        out.put("publicFutureCompletionFailures", publicFutureCompletionFailures.get());
        out.put("inFlightStatusCount", inFlightStatusCount.get());
        return out;
    }

    private Map<String, Object> debugSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stealAttempts", stealAttempts.get());
        out.put("stealSuccesses", stealSuccesses.get());
        out.put("localityHitRate", 0.0D);
        out.put("sameWorkerContinuations", sameWorkerContinuations.get());
        out.put("batchLocalDedupHits", batchLocalDedupHits.get());
        out.put("workTableWaiterCount", duplicateWorkJoined.get());
        out.put("readDependencyEdges", readDependencyEdges.get());
        out.put("resumeQueuePublishes", resumeQueuePublishes.get());
        return out;
    }

    private static long sum(AtomicLongArray array) {
        long value = 0L;
        for (int i = 0; i < array.length(); i++) {
            value += array.get(i);
        }
        return value;
    }

    private static void updateMax(AtomicLongArray array, int index, long value) {
        long current;
        do {
            current = array.get(index);
            if (value <= current) {
                return;
            }
        } while (!array.compareAndSet(index, current, value));
    }

    public enum FallbackReason {
        DISABLED("disabled"),
        STARTUP_PHASE("startup_phase"),
        COMPAT_CHUNK_SCHEDULER("compat_chunk_scheduler"),
        FORCE_LEGACY_STATUS("force_legacy_status"),
        UNSAFE_STATUS("unsafe_status"),
        PRESSURE("pressure"),
        ARENA_CAP("arena_cap"),
        EDGE_CAP("edge_cap"),
        WORK_TABLE_REJECTED("work_table_rejected"),
        DISPATCHER_FAILURE("dispatcher_failure"),
        TASK_FAILURE("task_failure"),
        ROLLBACK("rollback");

        private final String jsonName;

        FallbackReason(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }
    }
}
