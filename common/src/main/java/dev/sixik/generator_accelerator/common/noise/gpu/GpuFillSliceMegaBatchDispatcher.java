package dev.sixik.generator_accelerator.common.noise.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuIrPayload;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in collector for future cross-chunk fill-slice GPU mega-batches.
 *
 * <p>The default mode only collects and drains dry-run batches. The dispatch flag enables
 * a probe-only GPU launch path that validates cross-chunk packing without writing results
 * back into worldgen yet.
 */
public final class GpuFillSliceMegaBatchDispatcher {
    public static final String ENABLED_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch";
    public static final String DISPATCH_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.dispatch";
    public static final String ASYNC_PROBE_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.asyncProbe";
    public static final String BACKGROUND_DISPATCH_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.backgroundDispatch";
    public static final String PREFETCH_NEXT_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.prefetchNext";
    public static final String PREFETCH_LEAD_CELLS_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.prefetchLeadCells";
    public static final String WRITEBACK_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.writeback";
    public static final String WRITEBACK_WAIT_NANOS_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.writebackWaitNanos";
    public static final String PROBE_DISPATCH_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.probeDispatch";
    public static final String PROBE_PURE_PAYLOADS_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.probePurePayloads";
    public static final String TARGET_POINTS_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.targetPoints";
    public static final String PRESSURE_TARGET_POINTS_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.pressureTargetPoints";
    public static final String MAX_QUEUED_JOBS_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.maxQueuedJobs";
    public static final String COMPILE_MAX_PROPERTY = "ga.dfc.gpu.fillSliceMegaBatch.compileMax";

    private static final int DEFAULT_TARGET_POINTS = 262_144;
    private static final int DEFAULT_PREFETCH_LEAD_CELLS = 2;
    private static final int DEFAULT_MAX_QUEUED_JOBS = 512;
    private static final int DEFAULT_COMPILE_MAX = 4_096;

    private static final Object QUEUE_LOCK = new Object();
    private static final ExecutorService BACKGROUND_DISPATCH_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "GA FillSlice MegaBatch Dispatcher");
                thread.setDaemon(true);
                return thread;
            });
    private static final LinkedHashMap<PayloadShapeKey, Bucket> QUEUES_BY_SHAPE = new LinkedHashMap<>();
    private static final AtomicInteger QUEUED_JOBS = new AtomicInteger();
    private static final AtomicLong JOBS_ACCEPTED = new AtomicLong();
    private static final AtomicLong JOBS_REJECTED = new AtomicLong();
    private static final AtomicLong POINTS_ACCEPTED = new AtomicLong();
    private static final AtomicLong DRAINED_BATCHES = new AtomicLong();
    private static final AtomicLong DRAINED_JOBS = new AtomicLong();
    private static final AtomicLong DRAINED_POINTS = new AtomicLong();
    private static final AtomicLong DRAINED_BATCH_MAX_JOBS = new AtomicLong();
    private static final AtomicLong DRAINED_BATCH_MAX_POINTS = new AtomicLong();
    private static final AtomicLong DRAIN_DEFERRED_JOBS = new AtomicLong();
    private static final AtomicLong DRAIN_UNDERSIZED_BATCHES = new AtomicLong();
    private static final AtomicLong DRAINED_PURE_PAYLOAD_JOBS = new AtomicLong();
    private static final AtomicLong DRAINED_PURE_PAYLOAD_POINTS = new AtomicLong();
    private static final AtomicLong DRAINED_EXTERN_PAYLOAD_JOBS = new AtomicLong();
    private static final AtomicLong DRAINED_EXTERN_PAYLOAD_POINTS = new AtomicLong();
    private static final AtomicLong EXTERN_SNAPSHOT_JOBS = new AtomicLong();
    private static final AtomicLong EXTERN_SNAPSHOT_POINTS = new AtomicLong();
    private static final AtomicLong EXTERN_SNAPSHOT_MISSING_JOBS = new AtomicLong();
    private static final AtomicLong EXTERN_SNAPSHOT_MISSING_POINTS = new AtomicLong();
    private static final AtomicLong DISPATCH_ATTEMPTS = new AtomicLong();
    private static final AtomicLong DISPATCH_ATTEMPT_POINTS = new AtomicLong();
    private static final AtomicLong DISPATCH_GPU_SUCCESSES = new AtomicLong();
    private static final AtomicLong DISPATCH_GPU_SUCCESS_POINTS = new AtomicLong();
    private static final AtomicLong DISPATCH_FAILURES = new AtomicLong();
    private static final AtomicLong DISPATCH_FAILURE_POINTS = new AtomicLong();
    private static final AtomicLong DISPATCH_SKIPS = new AtomicLong();
    private static final AtomicLong DISPATCH_SKIP_POINTS = new AtomicLong();
    private static final AtomicLong BACKGROUND_DISPATCH_SUBMITS = new AtomicLong();
    private static final AtomicLong BACKGROUND_DISPATCH_ACCEPTED = new AtomicLong();
    private static final AtomicLong BACKGROUND_DISPATCH_STARTED = new AtomicLong();
    private static final AtomicLong BACKGROUND_DISPATCH_COMPLETED = new AtomicLong();
    private static final AtomicLong BACKGROUND_DISPATCH_REJECTED = new AtomicLong();
    private static final AtomicLong BACKGROUND_DISPATCH_FAILURES = new AtomicLong();
    private static final AtomicLong WRITEBACK_JOBS = new AtomicLong();
    private static final AtomicLong WRITEBACK_POINTS = new AtomicLong();
    private static final AtomicLong WRITEBACK_MISS_JOBS = new AtomicLong();
    private static final AtomicLong WRITEBACK_MISS_POINTS = new AtomicLong();
    private static final AtomicLong WRITEBACK_WAIT_ATTEMPTS = new AtomicLong();
    private static final AtomicLong WRITEBACK_WAIT_SUCCESSES = new AtomicLong();
    private static final AtomicLong WRITEBACK_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong PRESSURE_DRAIN_ATTEMPTS = new AtomicLong();
    private static final AtomicLong PRESSURE_DRAIN_SUCCESSES = new AtomicLong();
    private static final AtomicLong PRESSURE_DRAIN_POINTS = new AtomicLong();
    private static final AtomicLong PREFETCH_ATTEMPTS = new AtomicLong();
    private static final AtomicLong PREFETCH_QUEUED = new AtomicLong();
    private static final AtomicLong PREFETCH_DISPATCHES = new AtomicLong();
    private static final AtomicLong PREFETCH_CONSUME_ATTEMPTS = new AtomicLong();
    private static final AtomicLong PREFETCH_HITS = new AtomicLong();
    private static final AtomicLong PREFETCH_WRITEBACKS = new AtomicLong();
    private static final AtomicLong LIFECYCLE_SWAP_SLICES = new AtomicLong();
    private static final AtomicLong LIFECYCLE_FILL_SLICE0 = new AtomicLong();
    private static final AtomicLong LIFECYCLE_FILL_SLICE1 = new AtomicLong();
    private static final AtomicLong LIFECYCLE_LAST_SWAP_CELL_START = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong LIFECYCLE_LAST_FILL_SLICE0_START = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong LIFECYCLE_LAST_FILL_SLICE1_START = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong LIFECYCLE_LAST_FILL_SLICE0_TARGET = new AtomicLong();
    private static final AtomicLong LIFECYCLE_LAST_FILL_SLICE1_TARGET = new AtomicLong();
    private static final AtomicLong LIFECYCLE_LAST_PREFETCH_START = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong LIFECYCLE_LAST_PREFETCH_TARGET = new AtomicLong();
    private static final ConcurrentHashMap<String, LongAdder> PRESSURE_DRAIN_MISS_REASONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> PREFETCH_MISS_REASONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> WRITEBACK_MISS_REASONS =
            new ConcurrentHashMap<>();

    private GpuFillSliceMegaBatchDispatcher() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    public static boolean dispatchEnabled() {
        return Boolean.getBoolean(DISPATCH_PROPERTY);
    }

    public static boolean asyncProbeEnabled() {
        return Boolean.getBoolean(ASYNC_PROBE_PROPERTY);
    }

    public static boolean backgroundDispatchEnabled() {
        return Boolean.getBoolean(BACKGROUND_DISPATCH_PROPERTY);
    }

    public static boolean prefetchNextEnabled() {
        return Boolean.getBoolean(PREFETCH_NEXT_PROPERTY);
    }

    public static int prefetchLeadCells() {
        return Math.max(1, Integer.getInteger(PREFETCH_LEAD_CELLS_PROPERTY, DEFAULT_PREFETCH_LEAD_CELLS));
    }

    public static boolean writebackEnabled() {
        return Boolean.getBoolean(WRITEBACK_PROPERTY);
    }

    public static long writebackWaitNanos() {
        return Math.max(0L, Long.getLong(WRITEBACK_WAIT_NANOS_PROPERTY, 0L));
    }

    public static boolean probeDispatchEnabled() {
        return Boolean.getBoolean(PROBE_DISPATCH_PROPERTY);
    }

    public static boolean probePurePayloads() {
        return Boolean.getBoolean(PROBE_PURE_PAYLOADS_PROPERTY);
    }

    public static int targetPoints() {
        return Math.max(1, Integer.getInteger(TARGET_POINTS_PROPERTY, DEFAULT_TARGET_POINTS));
    }

    public static int pressureTargetPoints() {
        return Math.max(1, Integer.getInteger(PRESSURE_TARGET_POINTS_PROPERTY, targetPoints()));
    }

    public static int maxQueuedJobs() {
        return Math.max(1, Integer.getInteger(MAX_QUEUED_JOBS_PROPERTY, DEFAULT_MAX_QUEUED_JOBS));
    }

    public static int compileMax() {
        return Math.max(0, Integer.getInteger(COMPILE_MAX_PROPERTY, DEFAULT_COMPILE_MAX));
    }

    public static EnqueueResult enqueue(Job job) {
        Objects.requireNonNull(job, "job");
        if (!enabled()) {
            JOBS_REJECTED.incrementAndGet();
            return EnqueueResult.DISABLED;
        }
        if (job.rootCount() == 0 || job.combinedPointCount() <= 0) {
            JOBS_REJECTED.incrementAndGet();
            return EnqueueResult.EMPTY;
        }
        while (true) {
            int current = QUEUED_JOBS.get();
            if (current >= maxQueuedJobs()) {
                JOBS_REJECTED.incrementAndGet();
                return EnqueueResult.QUEUE_FULL;
            }
            if (QUEUED_JOBS.compareAndSet(current, current + 1)) {
                break;
            }
        }
        PayloadShapeKey shape = job.shapeKey();
        if (shape.maxExternInputs() > 0) {
            if (job.hasExternValuesSnapshot()) {
                EXTERN_SNAPSHOT_JOBS.incrementAndGet();
                EXTERN_SNAPSHOT_POINTS.addAndGet(job.combinedPointCount());
            } else {
                EXTERN_SNAPSHOT_MISSING_JOBS.incrementAndGet();
                EXTERN_SNAPSHOT_MISSING_POINTS.addAndGet(job.combinedPointCount());
            }
        }
        synchronized (QUEUE_LOCK) {
            Bucket bucket = QUEUES_BY_SHAPE.computeIfAbsent(shape, ignored -> new Bucket());
            bucket.jobs.addLast(job);
            bucket.points += job.combinedPointCount();
        }
        JOBS_ACCEPTED.incrementAndGet();
        POINTS_ACCEPTED.addAndGet(job.combinedPointCount());
        return EnqueueResult.QUEUED;
    }

    /**
     * Drains a compatible batch from the queue when enough points have accumulated.
     * Jobs are bucketed by payload shape to avoid repeatedly polling and requeueing
     * incompatible work while waiting for a launch-sized group.
     */
    public static Batch drainReadyBatch() {
        return drainReadyBatch(true);
    }

    public static Batch drainReadyBatch(boolean requireCompleted) {
        int minPoints = targetPoints();
        synchronized (QUEUE_LOCK) {
            if (QUEUES_BY_SHAPE.isEmpty()) {
                return Batch.empty();
            }
            Map.Entry<PayloadShapeKey, Bucket> ready = null;
            for (Map.Entry<PayloadShapeKey, Bucket> entry : QUEUES_BY_SHAPE.entrySet()) {
                Bucket bucket = entry.getValue();
                if (bucket.points >= minPoints) {
                    ready = entry;
                    break;
                }
            }
            if (ready == null) {
                DRAIN_UNDERSIZED_BATCHES.incrementAndGet();
                return Batch.empty();
            }

            PayloadShapeKey shape = ready.getKey();
            Bucket bucket = ready.getValue();
            ArrayList<Job> accepted = new ArrayList<>();
            long points = 0L;
            for (Job job : bucket.jobs) {
                if (requireCompleted && !job.completed()) {
                    break;
                }
                accepted.add(job);
                points += job.combinedPointCount();
                if (points >= minPoints) {
                    break;
                }
            }
            if (points < minPoints) {
                DRAIN_UNDERSIZED_BATCHES.incrementAndGet();
                return Batch.empty();
            }
            for (int i = 0; i < accepted.size(); i++) {
                bucket.jobs.removeFirst();
            }
            bucket.points -= points;
            QUEUED_JOBS.addAndGet(-accepted.size());
            if (bucket.jobs.isEmpty()) {
                QUEUES_BY_SHAPE.remove(shape);
            }

            recordDrainedBatch(shape, accepted.size(), points);
            return new Batch(List.copyOf(accepted), shape, points);
        }
    }

    public static Batch drainReadyBatchIncluding(Job requiredJob, boolean requireCompleted) {
        Objects.requireNonNull(requiredJob, "requiredJob");
        PRESSURE_DRAIN_ATTEMPTS.incrementAndGet();
        int minPoints = pressureTargetPoints();
        PayloadShapeKey shape = requiredJob.shapeKey();
        synchronized (QUEUE_LOCK) {
            Bucket bucket = QUEUES_BY_SHAPE.get(shape);
            if (bucket == null) {
                recordPressureDrainMiss("shape-missing");
                return Batch.empty();
            }
            if (bucket.points < minPoints) {
                recordPressureDrainMiss("below-target");
                return Batch.empty();
            }

            ArrayList<Job> queued = new ArrayList<>(bucket.jobs);
            int requiredIndex = -1;
            for (int i = 0; i < queued.size(); i++) {
                if (queued.get(i) == requiredJob) {
                    requiredIndex = i;
                    break;
                }
            }
            if (requiredIndex < 0) {
                recordPressureDrainMiss("required-missing");
                return Batch.empty();
            }
            if (requireCompleted && !requiredJob.completed()) {
                recordPressureDrainMiss("required-incomplete");
                return Batch.empty();
            }

            ArrayList<Job> accepted = new ArrayList<>();
            long points = 0L;
            for (int i = requiredIndex; i >= 0 && points < minPoints; i--) {
                Job candidate = queued.get(i);
                if (!requireCompleted || candidate.completed()) {
                    accepted.add(0, candidate);
                    points += candidate.combinedPointCount();
                }
            }
            for (int i = requiredIndex + 1; i < queued.size() && points < minPoints; i++) {
                Job candidate = queued.get(i);
                if (!requireCompleted || candidate.completed()) {
                    accepted.add(candidate);
                    points += candidate.combinedPointCount();
                }
            }
            if (points < minPoints) {
                recordPressureDrainMiss("not-enough-selected");
                return Batch.empty();
            }

            for (Job job : accepted) {
                bucket.jobs.remove(job);
            }
            bucket.points -= points;
            QUEUED_JOBS.addAndGet(-accepted.size());
            if (bucket.jobs.isEmpty()) {
                QUEUES_BY_SHAPE.remove(shape);
            }

            PRESSURE_DRAIN_SUCCESSES.incrementAndGet();
            PRESSURE_DRAIN_POINTS.addAndGet(points);
            recordDrainedBatch(shape, accepted.size(), points);
            return new Batch(List.copyOf(accepted), shape, points);
        }
    }

    public static boolean submitBackgroundDispatch(Batch batch, BackgroundDispatch dispatch) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(dispatch, "dispatch");
        if (!backgroundDispatchEnabled() || !batch.ready()) {
            return false;
        }
        BACKGROUND_DISPATCH_SUBMITS.incrementAndGet();
        try {
            BACKGROUND_DISPATCH_EXECUTOR.execute(() -> {
                BACKGROUND_DISPATCH_STARTED.incrementAndGet();
                try {
                    dispatch.dispatch(batch);
                    BACKGROUND_DISPATCH_COMPLETED.incrementAndGet();
                } catch (Throwable ignored) {
                    BACKGROUND_DISPATCH_FAILURES.incrementAndGet();
                }
            });
            for (Job job : batch.jobs()) {
                job.markBackgroundDispatchSubmitted();
            }
            BACKGROUND_DISPATCH_ACCEPTED.incrementAndGet();
            return true;
        } catch (RuntimeException exception) {
            BACKGROUND_DISPATCH_REJECTED.incrementAndGet();
            return false;
        }
    }

    private static void recordDrainedBatch(PayloadShapeKey shape, int jobCount, long points) {
        DRAINED_BATCHES.incrementAndGet();
        DRAINED_JOBS.addAndGet(jobCount);
        DRAINED_POINTS.addAndGet(points);
        updateMax(DRAINED_BATCH_MAX_JOBS, jobCount);
        updateMax(DRAINED_BATCH_MAX_POINTS, points);
        if (shape.maxExternInputs() == 0) {
            DRAINED_PURE_PAYLOAD_JOBS.addAndGet(jobCount);
            DRAINED_PURE_PAYLOAD_POINTS.addAndGet(points);
        } else {
            DRAINED_EXTERN_PAYLOAD_JOBS.addAndGet(jobCount);
            DRAINED_EXTERN_PAYLOAD_POINTS.addAndGet(points);
        }
    }

    public static Stats snapshotStats() {
        int shapeBuckets;
        int maxBucketJobs = 0;
        long maxBucketPoints = 0L;
        synchronized (QUEUE_LOCK) {
            shapeBuckets = QUEUES_BY_SHAPE.size();
            for (Bucket bucket : QUEUES_BY_SHAPE.values()) {
                maxBucketJobs = Math.max(maxBucketJobs, bucket.jobs.size());
                maxBucketPoints = Math.max(maxBucketPoints, bucket.points);
            }
        }
        return new Stats(
                enabled(),
                targetPoints(),
                pressureTargetPoints(),
                maxQueuedJobs(),
                compileMax(),
                QUEUED_JOBS.get(),
                shapeBuckets,
                maxBucketJobs,
                maxBucketPoints,
                JOBS_ACCEPTED.get(),
                JOBS_REJECTED.get(),
                POINTS_ACCEPTED.get(),
                DRAINED_BATCHES.get(),
                DRAINED_JOBS.get(),
                DRAINED_POINTS.get(),
                DRAINED_BATCH_MAX_JOBS.get(),
                DRAINED_BATCH_MAX_POINTS.get(),
                DRAIN_DEFERRED_JOBS.get(),
                DRAIN_UNDERSIZED_BATCHES.get(),
                DRAINED_PURE_PAYLOAD_JOBS.get(),
                DRAINED_PURE_PAYLOAD_POINTS.get(),
                DRAINED_EXTERN_PAYLOAD_JOBS.get(),
                DRAINED_EXTERN_PAYLOAD_POINTS.get(),
                EXTERN_SNAPSHOT_JOBS.get(),
                EXTERN_SNAPSHOT_POINTS.get(),
                EXTERN_SNAPSHOT_MISSING_JOBS.get(),
                EXTERN_SNAPSHOT_MISSING_POINTS.get(),
                dispatchEnabled(),
                asyncProbeEnabled(),
                prefetchNextEnabled(),
                prefetchLeadCells(),
                writebackEnabled(),
                writebackWaitNanos(),
                DISPATCH_ATTEMPTS.get(),
                DISPATCH_ATTEMPT_POINTS.get(),
                DISPATCH_GPU_SUCCESSES.get(),
                DISPATCH_GPU_SUCCESS_POINTS.get(),
                DISPATCH_FAILURES.get(),
                DISPATCH_FAILURE_POINTS.get(),
                DISPATCH_SKIPS.get(),
                DISPATCH_SKIP_POINTS.get(),
                backgroundDispatchEnabled(),
                BACKGROUND_DISPATCH_SUBMITS.get(),
                BACKGROUND_DISPATCH_ACCEPTED.get(),
                BACKGROUND_DISPATCH_STARTED.get(),
                BACKGROUND_DISPATCH_COMPLETED.get(),
                BACKGROUND_DISPATCH_REJECTED.get(),
                BACKGROUND_DISPATCH_FAILURES.get(),
                WRITEBACK_JOBS.get(),
                WRITEBACK_POINTS.get(),
                WRITEBACK_MISS_JOBS.get(),
                WRITEBACK_MISS_POINTS.get(),
                WRITEBACK_WAIT_ATTEMPTS.get(),
                WRITEBACK_WAIT_SUCCESSES.get(),
                WRITEBACK_WAIT_NANOS.get(),
                PRESSURE_DRAIN_ATTEMPTS.get(),
                PRESSURE_DRAIN_SUCCESSES.get(),
                PRESSURE_DRAIN_POINTS.get(),
                PREFETCH_ATTEMPTS.get(),
                PREFETCH_QUEUED.get(),
                PREFETCH_DISPATCHES.get(),
                PREFETCH_CONSUME_ATTEMPTS.get(),
                PREFETCH_HITS.get(),
                PREFETCH_WRITEBACKS.get(),
                LIFECYCLE_SWAP_SLICES.get(),
                LIFECYCLE_FILL_SLICE0.get(),
                LIFECYCLE_FILL_SLICE1.get(),
                LIFECYCLE_LAST_SWAP_CELL_START.get(),
                LIFECYCLE_LAST_FILL_SLICE0_START.get(),
                LIFECYCLE_LAST_FILL_SLICE1_START.get(),
                LIFECYCLE_LAST_FILL_SLICE0_TARGET.get(),
                LIFECYCLE_LAST_FILL_SLICE1_TARGET.get(),
                LIFECYCLE_LAST_PREFETCH_START.get(),
                LIFECYCLE_LAST_PREFETCH_TARGET.get(),
                snapshotPressureDrainMissReasons(),
                snapshotPrefetchMissReasons(),
                snapshotWritebackMissReasons());
    }

    public static void reset() {
        synchronized (QUEUE_LOCK) {
            QUEUES_BY_SHAPE.clear();
        }
        QUEUED_JOBS.set(0);
        JOBS_ACCEPTED.set(0L);
        JOBS_REJECTED.set(0L);
        POINTS_ACCEPTED.set(0L);
        DRAINED_BATCHES.set(0L);
        DRAINED_JOBS.set(0L);
        DRAINED_POINTS.set(0L);
        DRAINED_BATCH_MAX_JOBS.set(0L);
        DRAINED_BATCH_MAX_POINTS.set(0L);
        DRAIN_DEFERRED_JOBS.set(0L);
        DRAIN_UNDERSIZED_BATCHES.set(0L);
        DRAINED_PURE_PAYLOAD_JOBS.set(0L);
        DRAINED_PURE_PAYLOAD_POINTS.set(0L);
        DRAINED_EXTERN_PAYLOAD_JOBS.set(0L);
        DRAINED_EXTERN_PAYLOAD_POINTS.set(0L);
        EXTERN_SNAPSHOT_JOBS.set(0L);
        EXTERN_SNAPSHOT_POINTS.set(0L);
        EXTERN_SNAPSHOT_MISSING_JOBS.set(0L);
        EXTERN_SNAPSHOT_MISSING_POINTS.set(0L);
        DISPATCH_ATTEMPTS.set(0L);
        DISPATCH_ATTEMPT_POINTS.set(0L);
        DISPATCH_GPU_SUCCESSES.set(0L);
        DISPATCH_GPU_SUCCESS_POINTS.set(0L);
        DISPATCH_FAILURES.set(0L);
        DISPATCH_FAILURE_POINTS.set(0L);
        DISPATCH_SKIPS.set(0L);
        DISPATCH_SKIP_POINTS.set(0L);
        BACKGROUND_DISPATCH_SUBMITS.set(0L);
        BACKGROUND_DISPATCH_ACCEPTED.set(0L);
        BACKGROUND_DISPATCH_STARTED.set(0L);
        BACKGROUND_DISPATCH_COMPLETED.set(0L);
        BACKGROUND_DISPATCH_REJECTED.set(0L);
        BACKGROUND_DISPATCH_FAILURES.set(0L);
        WRITEBACK_JOBS.set(0L);
        WRITEBACK_POINTS.set(0L);
        WRITEBACK_MISS_JOBS.set(0L);
        WRITEBACK_MISS_POINTS.set(0L);
        WRITEBACK_WAIT_ATTEMPTS.set(0L);
        WRITEBACK_WAIT_SUCCESSES.set(0L);
        WRITEBACK_WAIT_NANOS.set(0L);
        PRESSURE_DRAIN_ATTEMPTS.set(0L);
        PRESSURE_DRAIN_SUCCESSES.set(0L);
        PRESSURE_DRAIN_POINTS.set(0L);
        PREFETCH_ATTEMPTS.set(0L);
        PREFETCH_QUEUED.set(0L);
        PREFETCH_DISPATCHES.set(0L);
        PREFETCH_CONSUME_ATTEMPTS.set(0L);
        PREFETCH_HITS.set(0L);
        PREFETCH_WRITEBACKS.set(0L);
        LIFECYCLE_SWAP_SLICES.set(0L);
        LIFECYCLE_FILL_SLICE0.set(0L);
        LIFECYCLE_FILL_SLICE1.set(0L);
        LIFECYCLE_LAST_SWAP_CELL_START.set(Long.MIN_VALUE);
        LIFECYCLE_LAST_FILL_SLICE0_START.set(Long.MIN_VALUE);
        LIFECYCLE_LAST_FILL_SLICE1_START.set(Long.MIN_VALUE);
        LIFECYCLE_LAST_FILL_SLICE0_TARGET.set(0L);
        LIFECYCLE_LAST_FILL_SLICE1_TARGET.set(0L);
        LIFECYCLE_LAST_PREFETCH_START.set(Long.MIN_VALUE);
        LIFECYCLE_LAST_PREFETCH_TARGET.set(0L);
        PRESSURE_DRAIN_MISS_REASONS.clear();
        PREFETCH_MISS_REASONS.clear();
        WRITEBACK_MISS_REASONS.clear();
    }

    public static void recordDispatchAttempt(long points) {
        DISPATCH_ATTEMPTS.incrementAndGet();
        DISPATCH_ATTEMPT_POINTS.addAndGet(points);
    }

    public static void recordDispatchGpuSuccess(long points) {
        DISPATCH_GPU_SUCCESSES.incrementAndGet();
        DISPATCH_GPU_SUCCESS_POINTS.addAndGet(points);
    }

    public static void recordDispatchFailure(long points) {
        DISPATCH_FAILURES.incrementAndGet();
        DISPATCH_FAILURE_POINTS.addAndGet(points);
    }

    public static void recordDispatchSkip(long points) {
        DISPATCH_SKIPS.incrementAndGet();
        DISPATCH_SKIP_POINTS.addAndGet(points);
    }

    public static void recordWriteback(long points) {
        WRITEBACK_JOBS.incrementAndGet();
        WRITEBACK_POINTS.addAndGet(points);
    }

    public static void recordWritebackMiss(String reason, long points) {
        WRITEBACK_MISS_JOBS.incrementAndGet();
        WRITEBACK_MISS_POINTS.addAndGet(points);
        WRITEBACK_MISS_REASONS.computeIfAbsent(normalizeReason(reason), ignored -> new LongAdder()).increment();
    }

    private static void recordPressureDrainMiss(String reason) {
        PRESSURE_DRAIN_MISS_REASONS.computeIfAbsent(normalizeReason(reason), ignored -> new LongAdder()).increment();
    }

    public static void recordWritebackWait(long nanos, boolean success) {
        WRITEBACK_WAIT_ATTEMPTS.incrementAndGet();
        if (success) {
            WRITEBACK_WAIT_SUCCESSES.incrementAndGet();
        }
        WRITEBACK_WAIT_NANOS.addAndGet(Math.max(0L, nanos));
    }

    public static void recordPrefetchAttempt() {
        PREFETCH_ATTEMPTS.incrementAndGet();
    }

    public static void recordPrefetchQueued() {
        PREFETCH_QUEUED.incrementAndGet();
    }

    public static void recordPrefetchDispatch() {
        PREFETCH_DISPATCHES.incrementAndGet();
    }

    public static void recordPrefetchConsumeAttempt() {
        PREFETCH_CONSUME_ATTEMPTS.incrementAndGet();
    }

    public static void recordPrefetchHit() {
        PREFETCH_HITS.incrementAndGet();
    }

    public static void recordPrefetchWriteback() {
        PREFETCH_WRITEBACKS.incrementAndGet();
    }

    public static void recordPrefetchMiss(String reason) {
        PREFETCH_MISS_REASONS.computeIfAbsent(normalizeReason(reason), ignored -> new LongAdder()).increment();
    }

    public static void recordLifecycleSwapSlices(int cellStartBlockX) {
        LIFECYCLE_SWAP_SLICES.incrementAndGet();
        LIFECYCLE_LAST_SWAP_CELL_START.set(cellStartBlockX);
    }

    public static void recordLifecycleFillSlice(boolean slice0, int start, double[] target) {
        if (slice0) {
            LIFECYCLE_FILL_SLICE0.incrementAndGet();
            LIFECYCLE_LAST_FILL_SLICE0_START.set(start);
            LIFECYCLE_LAST_FILL_SLICE0_TARGET.set(System.identityHashCode(target));
        } else {
            LIFECYCLE_FILL_SLICE1.incrementAndGet();
            LIFECYCLE_LAST_FILL_SLICE1_START.set(start);
            LIFECYCLE_LAST_FILL_SLICE1_TARGET.set(System.identityHashCode(target));
        }
    }

    public static void recordLifecyclePrefetchStored(int start, double[] target) {
        LIFECYCLE_LAST_PREFETCH_START.set(start);
        LIFECYCLE_LAST_PREFETCH_TARGET.set(System.identityHashCode(target));
    }

    private static List<String> snapshotWritebackMissReasons() {
        return snapshotReasons(WRITEBACK_MISS_REASONS);
    }

    private static List<String> snapshotPressureDrainMissReasons() {
        return snapshotReasons(PRESSURE_DRAIN_MISS_REASONS);
    }

    private static List<String> snapshotPrefetchMissReasons() {
        return snapshotReasons(PREFETCH_MISS_REASONS);
    }

    private static List<String> snapshotReasons(ConcurrentHashMap<String, LongAdder> reasons) {
        ArrayList<String> snapshot = new ArrayList<>();
        reasons.forEach((reason, count) -> snapshot.add(reason + "=" + count.sum()));
        snapshot.sort(String::compareTo);
        return List.copyOf(snapshot);
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.trim();
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) {
                return;
            }
        } while (!target.compareAndSet(current, value));
    }

    private static final class Bucket {
        private final ArrayDeque<Job> jobs = new ArrayDeque<>();
        private long points;
    }

    public enum EnqueueResult {
        QUEUED,
        DISABLED,
        EMPTY,
        QUEUE_FULL
    }

    public static final class CandidateRoot {
        private final int targetIndex;
        private final CompiledDensityFunction root;
        private final GpuIrPayload payload;

        public CandidateRoot(int targetIndex, CompiledDensityFunction root, GpuIrPayload payload) {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(payload, "payload");
            if (targetIndex < 0) {
                throw new IllegalArgumentException("targetIndex must be non-negative");
            }
            this.targetIndex = targetIndex;
            this.root = root;
            this.payload = payload;
        }

        CandidateRoot(int targetIndex, GpuIrPayload payload) {
            Objects.requireNonNull(payload, "payload");
            if (targetIndex < 0) {
                throw new IllegalArgumentException("targetIndex must be non-negative");
            }
            this.targetIndex = targetIndex;
            this.root = null;
            this.payload = payload;
        }

        public int targetIndex() {
            return targetIndex;
        }

        public CompiledDensityFunction root() {
            return root;
        }

        public GpuIrPayload payload() {
            return payload;
        }
    }

    public static final class Job {
        private final int columns;
        private final int yCount;
        private final int pointCount;
        private final int planeSize;
        private final long arrayCounterBase;
        private final int cellStartBlockX;
        private final int firstCellZ;
        private final int cellWidth;
        private final int cellNoiseMinY;
        private final int cellHeight;
        private final int externInputStride;
        private final double[] externValuesSnapshot;
        private final double[] target;
        private final CandidateRoot[] roots;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean backgroundDispatchSubmitted = new AtomicBoolean();
        private final AtomicBoolean gpuDispatchStarted = new AtomicBoolean();
        private final AtomicBoolean gpuDispatchInFlight = new AtomicBoolean();
        private final AtomicBoolean runtimeParityChecked = new AtomicBoolean();
        private final AtomicBoolean runtimeParityPassed = new AtomicBoolean();
        private volatile double[] targetValuesSnapshot;
        private volatile double[] gpuValuesSnapshot;

        public Job(
                int columns,
                int yCount,
                int pointCount,
                int planeSize,
                long arrayCounterBase,
                int cellStartBlockX,
                int firstCellZ,
                int cellWidth,
                int cellNoiseMinY,
                int cellHeight,
                int externInputStride,
                double[] externValuesSnapshot,
                double[] target,
                CandidateRoot[] roots) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(roots, "roots");
            if (columns <= 0 || yCount <= 0 || pointCount <= 0 || planeSize <= 0
                    || cellWidth <= 0 || cellHeight <= 0 || externInputStride < 0) {
                throw new IllegalArgumentException("dimensions must be positive");
            }
            CandidateRoot[] rootsCopy = roots.clone();
            for (CandidateRoot root : rootsCopy) {
                Objects.requireNonNull(root, "root");
            }
            double[] externSnapshot = externValuesSnapshot;
            if (externInputStride > 0 && externSnapshot != null) {
                int requiredExternValues = Math.multiplyExact(
                        Math.multiplyExact(pointCount, rootsCopy.length), externInputStride);
                if (externSnapshot.length < requiredExternValues) {
                    throw new IllegalArgumentException("externValuesSnapshot length " + externSnapshot.length
                            + " is smaller than required length " + requiredExternValues);
                }
            } else if (externSnapshot != null && externSnapshot.length != 0) {
                throw new IllegalArgumentException("externValuesSnapshot must be empty when externInputStride is zero");
            }
            this.columns = columns;
            this.yCount = yCount;
            this.pointCount = pointCount;
            this.planeSize = planeSize;
            this.arrayCounterBase = arrayCounterBase;
            this.cellStartBlockX = cellStartBlockX;
            this.firstCellZ = firstCellZ;
            this.cellWidth = cellWidth;
            this.cellNoiseMinY = cellNoiseMinY;
            this.cellHeight = cellHeight;
            this.externInputStride = externInputStride;
            this.externValuesSnapshot = externSnapshot;
            this.target = target;
            this.roots = rootsCopy;
        }

        public int columns() {
            return columns;
        }

        public int yCount() {
            return yCount;
        }

        public int pointCount() {
            return pointCount;
        }

        public int planeSize() {
            return planeSize;
        }

        public long arrayCounterBase() {
            return arrayCounterBase;
        }

        public int cellStartBlockX() {
            return cellStartBlockX;
        }

        public int firstCellZ() {
            return firstCellZ;
        }

        public int cellWidth() {
            return cellWidth;
        }

        public int cellNoiseMinY() {
            return cellNoiseMinY;
        }

        public int cellHeight() {
            return cellHeight;
        }

        public int externInputStride() {
            return externInputStride;
        }

        public boolean hasExternValuesSnapshot() {
            return externInputStride > 0 && externValuesSnapshot != null;
        }

        public boolean completed() {
            return completed.get();
        }

        public boolean gpuDispatchStarted() {
            return gpuDispatchStarted.get();
        }

        public boolean gpuDispatchInFlight() {
            return gpuDispatchInFlight.get();
        }

        public boolean backgroundDispatchSubmitted() {
            return backgroundDispatchSubmitted.get();
        }

        public boolean runtimeParityChecked() {
            return runtimeParityChecked.get();
        }

        public boolean runtimeParityPassed() {
            return runtimeParityPassed.get();
        }

        public void markBackgroundDispatchSubmitted() {
            backgroundDispatchSubmitted.set(true);
        }

        public void markGpuDispatchStarted() {
            gpuDispatchStarted.set(true);
            gpuDispatchInFlight.set(true);
        }

        public void markGpuDispatchFinished() {
            gpuDispatchInFlight.set(false);
        }

        public void markRuntimeParity(boolean passed) {
            runtimeParityPassed.set(passed);
            runtimeParityChecked.set(true);
        }

        public void markCompleted() {
            double[] snapshot = new double[Math.multiplyExact(pointCount, roots.length)];
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                System.arraycopy(
                        target,
                        roots[rootIndex].targetIndex() * planeSize,
                        snapshot,
                        rootIndex * pointCount,
                        pointCount);
            }
            targetValuesSnapshot = snapshot;
            completed.set(true);
        }

        public double[] targetValuesSnapshot() {
            return targetValuesSnapshot;
        }

        public void markGpuCompleted(double[] gpuOutput, int payloadOffset) {
            Objects.requireNonNull(gpuOutput, "gpuOutput");
            if (payloadOffset < 0) {
                throw new IllegalArgumentException("payloadOffset must be non-negative");
            }
            int valueCount = Math.multiplyExact(pointCount, roots.length);
            int sourceOffset = Math.multiplyExact(payloadOffset, pointCount);
            if (gpuOutput.length < sourceOffset + valueCount) {
                throw new IllegalArgumentException("gpuOutput length " + gpuOutput.length
                        + " is smaller than required end offset " + (sourceOffset + valueCount));
            }
            double[] snapshot = new double[valueCount];
            System.arraycopy(gpuOutput, sourceOffset, snapshot, 0, valueCount);
            gpuValuesSnapshot = snapshot;
        }

        public double[] gpuValuesSnapshot() {
            return gpuValuesSnapshot;
        }

        public boolean writeGpuValuesToTarget() {
            double[] gpuSnapshot = gpuValuesSnapshot;
            int valueCount = Math.multiplyExact(pointCount, roots.length);
            if (gpuSnapshot == null || gpuSnapshot.length < valueCount) {
                return false;
            }
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                System.arraycopy(
                        gpuSnapshot,
                        rootIndex * pointCount,
                        target,
                        roots[rootIndex].targetIndex() * planeSize,
                        pointCount);
            }
            return true;
        }

        public double[] externValuesSnapshot() {
            return externValuesSnapshot;
        }

        public double[] target() {
            return target;
        }

        public CandidateRoot[] roots() {
            return roots.clone();
        }

        public int rootCount() {
            return roots.length;
        }

        public long combinedPointCount() {
            return (long) pointCount * roots.length;
        }

        public PayloadShapeKey shapeKey() {
            return PayloadShapeKey.from(pointCount, roots);
        }

        public void fillCoordinates(int[] blockX, int[] blockY, int[] blockZ, int pointOffset) {
            Objects.requireNonNull(blockX, "blockX");
            Objects.requireNonNull(blockY, "blockY");
            Objects.requireNonNull(blockZ, "blockZ");
            int end = Math.addExact(pointOffset, pointCount);
            if (pointOffset < 0 || blockX.length < end || blockY.length < end || blockZ.length < end) {
                throw new IllegalArgumentException("coordinate arrays must cover job points");
            }
            int idx = pointOffset;
            for (int column = 0; column < columns; column++) {
                int startZ = (firstCellZ + column) * cellWidth;
                for (int y = 0; y < yCount; y++) {
                    blockX[idx] = cellStartBlockX;
                    blockY[idx] = (y + cellNoiseMinY) * cellHeight;
                    blockZ[idx] = startZ;
                    idx++;
                }
            }
        }
    }

    public record PayloadShapeKey(
            int pointCount,
            int rootCount,
            int totalNodes,
            int maxExternInputs,
            int totalNoisePermutations,
            int totalNoiseOctaveData,
            boolean hasCustomOps) {
        static PayloadShapeKey from(int pointCount, CandidateRoot[] roots) {
            int totalNodes = 0;
            int maxExternInputs = 0;
            int totalNoisePermutations = 0;
            int totalNoiseOctaveData = 0;
            boolean hasCustomOps = false;
            for (CandidateRoot root : roots) {
                GpuIrPayload payload = root.payload();
                totalNodes = Math.addExact(totalNodes, payload.nodeCount());
                maxExternInputs = Math.max(maxExternInputs, payload.externInputCount());
                totalNoisePermutations = Math.addExact(totalNoisePermutations, payload.noisePermutations().length);
                totalNoiseOctaveData = Math.addExact(totalNoiseOctaveData, payload.noiseOctaveData().length);
                hasCustomOps |= payload.hasCustomOps();
            }
            return new PayloadShapeKey(
                    pointCount, roots.length, totalNodes, maxExternInputs, totalNoisePermutations,
                    totalNoiseOctaveData, hasCustomOps);
        }
    }

    public record Batch(List<Job> jobs, PayloadShapeKey shapeKey, long combinedPointCount) {
        static Batch empty() {
            return new Batch(List.of(), new PayloadShapeKey(0, 0, 0, 0, 0, 0, false), 0L);
        }

        public boolean ready() {
            return !jobs.isEmpty();
        }
    }

    @FunctionalInterface
    public interface BackgroundDispatch {
        void dispatch(Batch batch);
    }

    public record Stats(
            boolean enabled,
            int targetPoints,
            int pressureTargetPoints,
            int maxQueuedJobs,
            int compileMax,
            int queuedJobs,
            int shapeBuckets,
            int maxBucketJobs,
            long maxBucketPoints,
            long jobsAccepted,
            long jobsRejected,
            long pointsAccepted,
            long drainedBatches,
            long drainedJobs,
            long drainedPoints,
            long drainedBatchMaxJobs,
            long drainedBatchMaxPoints,
            long drainDeferredJobs,
            long drainUndersizedBatches,
            long drainedPurePayloadJobs,
            long drainedPurePayloadPoints,
            long drainedExternPayloadJobs,
            long drainedExternPayloadPoints,
            long externSnapshotJobs,
            long externSnapshotPoints,
            long externSnapshotMissingJobs,
            long externSnapshotMissingPoints,
            boolean dispatchEnabled,
            boolean asyncProbeEnabled,
            boolean prefetchNextEnabled,
            int prefetchLeadCells,
            boolean writebackEnabled,
            long writebackWaitNanos,
            long dispatchAttempts,
            long dispatchAttemptPoints,
            long dispatchGpuSuccesses,
            long dispatchGpuSuccessPoints,
            long dispatchFailures,
            long dispatchFailurePoints,
            long dispatchSkips,
            long dispatchSkipPoints,
            boolean backgroundDispatchEnabled,
            long backgroundDispatchSubmits,
            long backgroundDispatchAccepted,
            long backgroundDispatchStarted,
            long backgroundDispatchCompleted,
            long backgroundDispatchRejected,
            long backgroundDispatchFailures,
            long writebackJobs,
            long writebackPoints,
            long writebackMissJobs,
            long writebackMissPoints,
            long writebackWaitAttempts,
            long writebackWaitSuccesses,
            long writebackWaitNanosTotal,
            long pressureDrainAttempts,
            long pressureDrainSuccesses,
            long pressureDrainPoints,
            long prefetchAttempts,
            long prefetchQueued,
            long prefetchDispatches,
            long prefetchConsumeAttempts,
            long prefetchHits,
            long prefetchWritebacks,
            long lifecycleSwapSlices,
            long lifecycleFillSlice0,
            long lifecycleFillSlice1,
            long lifecycleLastSwapCellStart,
            long lifecycleLastFillSlice0Start,
            long lifecycleLastFillSlice1Start,
            long lifecycleLastFillSlice0Target,
            long lifecycleLastFillSlice1Target,
            long lifecycleLastPrefetchStart,
            long lifecycleLastPrefetchTarget,
            List<String> pressureDrainMissReasons,
            List<String> prefetchMissReasons,
            List<String> writebackMissReasons) {
    }
}
