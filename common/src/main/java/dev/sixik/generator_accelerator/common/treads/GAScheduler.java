package dev.sixik.generator_accelerator.common.treads;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACrossChunkMailboxRuntime;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import dev.sixik.generator_accelerator.diagnostics.GAWallTimeTelemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * GA-owned scheduler facade. Compile work keeps a dedicated pool; worldgen lanes
 * are logical lanes over one adaptive worker pool with scheduler-side spatial
 * admission.
 */
public final class GAScheduler {
    private static final Object INIT_LOCK = new Object();
    private static final AtomicLongArray SUBMITTED = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray COMPLETED = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray FAILED = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray INLINE_RUNS = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray EXECUTION_NANOS = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray MAX_ACTIVE = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray MAX_QUEUED = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray ADMISSION_ACCEPTED = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray ADMISSION_REJECTED = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray GOVERNOR_THROTTLED = new AtomicLongArray(Lane.values().length);
    private static final AtomicLongArray GOVERNOR_WAIT_NANOS = new AtomicLongArray(Lane.values().length);
    private static final AtomicInteger[] ACTIVE = new AtomicInteger[Lane.values().length];
    private static final AtomicInteger[] ADMITTED_QUEUED = new AtomicInteger[Lane.values().length];
    private static final AtomicInteger COMPILE_GOVERNOR_RUNNING = new AtomicInteger();
    private static final AtomicLong MAX_WORLDGEN_PRESSURE = new AtomicLong();
    private static final AtomicLong MAX_COMMIT_BACKLOG = new AtomicLong();
    private static final AtomicLong MAX_MAILBOX_BACKLOG = new AtomicLong();
    private static final AtomicLong BOTTLENECK_THROTTLES = new AtomicLong();
    private static final ThreadLocal<GAScheduledTask> CURRENT_TASK = new ThreadLocal<>();

    private static volatile boolean initialized;
    private static volatile ForkJoinPool noisePool;
    private static volatile ForkJoinPool compilePool;
    private static volatile ForkJoinPool workspacePool;
    private static volatile ForkJoinPool transactionalPool;
    private static volatile ForkJoinPool serialPool;
    private static volatile ForkJoinPool commitPool;
    private static volatile GAAdaptiveWorldgenScheduler adaptiveWorldgen;
    private static volatile ConfigSnapshot configSnapshot = ConfigSnapshot.defaults();

    static {
        for (int i = 0; i < ACTIVE.length; i++) {
            ACTIVE[i] = new AtomicInteger();
            ADMITTED_QUEUED[i] = new AtomicInteger();
        }
    }

    private GAScheduler() {
    }

    public static void init(boolean isDev) {
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }

            GAConfig config = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            ConfigSnapshot snapshot = ConfigSnapshot.from(config, processors, isDev);

            configSnapshot = snapshot;
            adaptiveWorldgen = new GAAdaptiveWorldgenScheduler(snapshot);
            initialized = true;
        }
    }

    /**
     * Legacy executor accessors are retained for old call sites and tests. Runtime
     * scheduling through this class uses the adaptive worldgen pool for worldgen lanes.
     */
    public static ForkJoinPool noisePool() {
        return forkJoinPool(Lane.NOISE);
    }

    public static ForkJoinPool compilePool() {
        return forkJoinPool(Lane.COMPILE);
    }

    public static ForkJoinPool workspacePool() {
        return forkJoinPool(Lane.WORKSPACE);
    }

    public static ForkJoinPool transactionalPool() {
        return forkJoinPool(Lane.TRANSACTIONAL);
    }

    public static ForkJoinPool serialPool() {
        return forkJoinPool(Lane.SERIAL);
    }

    public static ForkJoinPool commitPool() {
        return forkJoinPool(Lane.COMMIT);
    }

    public static ForkJoinPool forkJoinPool(Lane lane) {
        ensureInitialized();
        ForkJoinPool pool = existingPool(lane);
        if (pool != null) {
            return pool;
        }
        synchronized (INIT_LOCK) {
            pool = existingPool(lane);
            if (pool == null) {
                pool = newPool(lane, workersFor(lane, configSnapshot));
                setPool(lane, pool);
            }
            return pool;
        }
    }

    public static ConflictRegion conflictRegion(int chunkX, int chunkZ, int radius) {
        ensureInitialized();
        if (radius <= 0) {
            return null;
        }
        return ConflictRegion.of(chunkX, chunkZ, radius, configSnapshot.spatialConflictStripes());
    }

    public static boolean currentTaskHoldsConflictRegion() {
        GAScheduledTask task = CURRENT_TASK.get();
        return task != null && task.region != null;
    }

    public static void retainCurrentConflictRegionUntil(CompletableFuture<?> future) {
        if (future == null) {
            return;
        }
        GAScheduledTask task = CURRENT_TASK.get();
        if (task == null || task.region == null || task.regionReleased.get()) {
            return;
        }
        if (task.regionRetained.compareAndSet(false, true)) {
            future.whenComplete((ignored, throwable) -> task.releaseRetainedRegion());
        }
    }

    public static <T> CompletableFuture<T> supplyAsync(Lane lane, Supplier<T> supplier) {
        return supplyAsync(lane, 0, null, supplier);
    }

    public static <T> CompletableFuture<T> supplyAsync(
            Lane lane,
            int priority,
            ConflictRegion region,
            Supplier<T> supplier
    ) {
        ensureInitialized();
        Supplier<T> task = wrapWorkspaceContext(supplier);
        int index = lane.ordinal();
        SUBMITTED.incrementAndGet(index);
        if (isCurrentLaneWorker(lane)) {
            INLINE_RUNS.incrementAndGet(index);
            ADMISSION_ACCEPTED.incrementAndGet(index);
            try {
                return CompletableFuture.completedFuture(runMeasured(lane, task));
            } catch (Throwable throwable) {
                return failedFuture(throwable);
            }
        }
        if (lane == Lane.COMPILE) {
            return supplyOnForkJoin(lane, task);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicBoolean queueSlotHeld = reserveQueueSlotOrReject(lane, future);
        if (future.isCompletedExceptionally()) {
            return future;
        }

        GAScheduledTask scheduled = new GAScheduledTask(
                lane,
                priority,
                region,
                false,
                queueSlotHeld,
                () -> {
                    if (!future.isCancelled()) {
                        completeFromSupplier(future, () -> runMeasured(lane, task));
                    }
                },
                throwable -> future.completeExceptionally(throwable)
        );
        future.whenComplete((ignored, throwable) -> {
            if (future.isCancelled()) {
                scheduled.cancel();
            }
        });
        adaptiveWorldgen.submit(scheduled);
        return future;
    }

    /**
     * Runs bounded nested worldgen work. Nested work bypasses credit throttling,
     * but still respects spatial conflict regions unless it is inlined to avoid a
     * self-deadlock.
     */
    public static <T> CompletableFuture<T> supplyNestedAsync(Lane lane, Supplier<T> supplier) {
        ensureInitialized();
        Supplier<T> task = wrapWorkspaceContext(supplier);
        int index = lane.ordinal();
        SUBMITTED.incrementAndGet(index);
        if (isCurrentLaneWorker(lane) || shouldInlineNestedFromGaWorker(lane)) {
            INLINE_RUNS.incrementAndGet(index);
            ADMISSION_ACCEPTED.incrementAndGet(index);
            try {
                return CompletableFuture.completedFuture(runMeasured(lane, task));
            } catch (Throwable throwable) {
                return failedFuture(throwable);
            }
        }
        if (lane == Lane.COMPILE) {
            return supplyNestedOnForkJoin(lane, task);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        ADMISSION_ACCEPTED.incrementAndGet(index);
        GAScheduledTask scheduled = new GAScheduledTask(
                lane,
                0,
                null,
                true,
                null,
                () -> {
                    if (!future.isCancelled()) {
                        completeFromSupplier(future, () -> runMeasured(lane, task));
                    }
                },
                throwable -> future.completeExceptionally(throwable)
        );
        future.whenComplete((ignored, throwable) -> {
            if (future.isCancelled()) {
                scheduled.cancel();
            }
        });
        adaptiveWorldgen.submit(scheduled);
        return future;
    }

    public static void execute(Lane lane, Runnable runnable) {
        executeAsync(lane, runnable, null);
    }

    public static void executeAsync(Lane lane, Runnable runnable, Consumer<Throwable> failureHandler) {
        executeAsync(lane, 0, null, runnable, failureHandler);
    }

    public static void executeAsync(
            Lane lane,
            int priority,
            ConflictRegion region,
            Runnable runnable,
            Consumer<Throwable> failureHandler
    ) {
        ensureInitialized();
        Supplier<Void> task = wrapWorkspaceContext(() -> {
            runnable.run();
            return null;
        });
        int index = lane.ordinal();
        SUBMITTED.incrementAndGet(index);
        if (isCurrentLaneWorker(lane)) {
            INLINE_RUNS.incrementAndGet(index);
            ADMISSION_ACCEPTED.incrementAndGet(index);
            runMeasuredWithFailureCallback(lane, task, failureHandler);
            return;
        }
        if (lane == Lane.COMPILE) {
            executeOnForkJoin(lane, task, failureHandler);
            return;
        }

        AtomicBoolean queueSlotHeld = reserveQueueSlotOrNotify(lane, failureHandler);
        if (queueSlotHeld == QUEUE_REJECTED) {
            return;
        }
        adaptiveWorldgen.submit(new GAScheduledTask(
                lane,
                priority,
                region,
                false,
                queueSlotHeld,
                () -> runMeasuredWithFailureCallback(lane, task, failureHandler),
                failureHandler
        ));
    }

    /**
     * Fire-and-callback variant of {@link #supplyNestedAsync(Lane, Supplier)} for nested work that is joined by
     * caller-owned state instead of one CompletableFuture per child task.
     */
    public static void executeNestedAsync(Lane lane, Runnable runnable, Consumer<Throwable> failureHandler) {
        ensureInitialized();
        Supplier<Void> task = wrapWorkspaceContext(() -> {
            runnable.run();
            return null;
        });
        int index = lane.ordinal();
        SUBMITTED.incrementAndGet(index);
        if (isCurrentLaneWorker(lane) || shouldInlineNestedFromGaWorker(lane)) {
            INLINE_RUNS.incrementAndGet(index);
            ADMISSION_ACCEPTED.incrementAndGet(index);
            runMeasuredWithFailureCallback(lane, task, failureHandler);
            return;
        }
        if (lane == Lane.COMPILE) {
            executeNestedOnForkJoin(lane, task, failureHandler);
            return;
        }
        ADMISSION_ACCEPTED.incrementAndGet(index);
        adaptiveWorldgen.submit(new GAScheduledTask(
                lane,
                0,
                null,
                true,
                null,
                () -> runMeasuredWithFailureCallback(lane, task, failureHandler),
                failureHandler
        ));
    }

    public static void invokeBlocking(Lane lane, Runnable runnable) throws InterruptedException, ExecutionException {
        ensureInitialized();
        if (isCurrentGaWorker()) {
            int index = lane.ordinal();
            SUBMITTED.incrementAndGet(index);
            INLINE_RUNS.incrementAndGet(index);
            ADMISSION_ACCEPTED.incrementAndGet(index);
            runMeasured(lane, () -> {
                runnable.run();
                return null;
            });
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        executeAsync(lane, () -> {
            try {
                runnable.run();
            } finally {
                done.countDown();
            }
        }, throwable -> {
            failure.compareAndSet(null, throwable);
            done.countDown();
        });
        done.await();
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new ExecutionException(throwable);
        }
    }

    public static Map<String, Object> snapshot() {
        ensureInitialized();
        Map<String, Object> out = new LinkedHashMap<>();
        ConfigSnapshot config = configSnapshot;
        out.put("config", config.toMap());
        Map<String, Object> lanes = new LinkedHashMap<>();
        for (Lane lane : Lane.values()) {
            lanes.put(lane.jsonName(), laneSnapshot(lane));
        }
        out.put("lanes", lanes);
        out.put("adaptive", adaptiveWorldgen.snapshot());
        out.put("spatial", adaptiveWorldgen.spatialSnapshot());
        out.put("governor", governorSnapshot());
        return out;
    }

    public static String summary() {
        ensureInitialized();
        StringBuilder builder = new StringBuilder(256);
        builder.append("adaptive(worldgenWorkers=")
                .append(configSnapshot.worldgenWorkers())
                .append(", active=")
                .append(adaptiveWorldgen.activeWorkers())
                .append(", queued=")
                .append(adaptiveWorldgen.totalQueued())
                .append(")");
        for (Lane lane : Lane.values()) {
            int index = lane.ordinal();
            builder.append("; ")
                    .append(lane.jsonName())
                    .append("(credits=").append(lane == Lane.COMPILE ? 0 : adaptiveWorldgen.credits(lane))
                    .append(", active=").append(ACTIVE[index].get())
                    .append(", queued=").append(queuedTaskEstimate(lane))
                    .append(", submitted=").append(SUBMITTED.get(index))
                    .append(", completed=").append(COMPLETED.get(index))
                    .append(')');
        }
        return builder.toString();
    }

    public static void resetMetrics() {
        for (int i = 0; i < Lane.values().length; i++) {
            SUBMITTED.set(i, 0L);
            COMPLETED.set(i, 0L);
            FAILED.set(i, 0L);
            INLINE_RUNS.set(i, 0L);
            EXECUTION_NANOS.set(i, 0L);
            MAX_ACTIVE.set(i, 0L);
            MAX_QUEUED.set(i, 0L);
            ADMISSION_ACCEPTED.set(i, 0L);
            ADMISSION_REJECTED.set(i, 0L);
            GOVERNOR_THROTTLED.set(i, 0L);
            GOVERNOR_WAIT_NANOS.set(i, 0L);
        }
        MAX_WORLDGEN_PRESSURE.set(0L);
        MAX_COMMIT_BACKLOG.set(0L);
        MAX_MAILBOX_BACKLOG.set(0L);
        BOTTLENECK_THROTTLES.set(0L);
        COMPILE_GOVERNOR_RUNNING.set(0);
        resetAdmissionForTests();
        GAAdaptiveWorldgenScheduler scheduler = adaptiveWorldgen;
        if (scheduler != null) {
            scheduler.resetMetrics();
        }
    }

    public static void shutdownForTests() {
        ForkJoinPool oldNoisePool;
        ForkJoinPool oldCompilePool;
        ForkJoinPool oldWorkspacePool;
        ForkJoinPool oldTransactionalPool;
        ForkJoinPool oldSerialPool;
        ForkJoinPool oldCommitPool;
        GAAdaptiveWorldgenScheduler oldAdaptive;

        synchronized (INIT_LOCK) {
            oldNoisePool = noisePool;
            oldCompilePool = compilePool;
            oldWorkspacePool = workspacePool;
            oldTransactionalPool = transactionalPool;
            oldSerialPool = serialPool;
            oldCommitPool = commitPool;
            oldAdaptive = adaptiveWorldgen;

            noisePool = null;
            compilePool = null;
            workspacePool = null;
            transactionalPool = null;
            serialPool = null;
            commitPool = null;
            adaptiveWorldgen = null;
            configSnapshot = ConfigSnapshot.defaults();
            resetMetrics();
            initialized = false;
        }

        if (oldAdaptive != null) {
            oldAdaptive.shutdown();
        }
        shutdownPool(oldNoisePool);
        shutdownPool(oldCompilePool);
        shutdownPool(oldWorkspacePool);
        shutdownPool(oldTransactionalPool);
        shutdownPool(oldSerialPool);
        shutdownPool(oldCommitPool);
    }

    private static Map<String, Object> laneSnapshot(Lane lane) {
        ForkJoinPool pool = lane == Lane.COMPILE ? existingPool(lane) : null;
        int index = lane.ordinal();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parallelism", lane == Lane.COMPILE
                ? (pool == null ? workersFor(lane, configSnapshot) : pool.getParallelism())
                : configSnapshot.worldgenWorkers());
        out.put("poolSize", lane == Lane.COMPILE ? (pool == null ? 0 : pool.getPoolSize()) : configSnapshot.worldgenWorkers());
        out.put("runningThreadCount", lane == Lane.COMPILE ? (pool == null ? 0 : pool.getRunningThreadCount()) : adaptiveWorldgen.runningWorkers());
        out.put("activeThreadCount", lane == Lane.COMPILE ? (pool == null ? 0 : pool.getActiveThreadCount()) : adaptiveWorldgen.activeWorkers());
        out.put("activeTasks", ACTIVE[index].get());
        out.put("queuedTaskEstimate", queuedTaskEstimate(lane));
        out.put("queuedAdmissionSlots", ADMITTED_QUEUED[index].get());
        out.put("queuedSubmissionCount", lane == Lane.COMPILE ? (pool == null ? 0 : pool.getQueuedSubmissionCount()) : adaptiveWorldgen.queued(lane));
        out.put("stealCount", lane == Lane.COMPILE ? (pool == null ? 0L : pool.getStealCount()) : 0L);
        out.put("submitted", SUBMITTED.get(index));
        out.put("completed", COMPLETED.get(index));
        out.put("failed", FAILED.get(index));
        out.put("inlineRuns", INLINE_RUNS.get(index));
        out.put("admissionAccepted", ADMISSION_ACCEPTED.get(index));
        out.put("admissionRejected", ADMISSION_REJECTED.get(index));
        out.put("governorThrottled", GOVERNOR_THROTTLED.get(index));
        out.put("governorWaitNanos", GOVERNOR_WAIT_NANOS.get(index));
        out.put("executionNanos", EXECUTION_NANOS.get(index));
        out.put("maxActiveTasks", MAX_ACTIVE.get(index));
        out.put("maxQueuedTaskEstimate", MAX_QUEUED.get(index));
        out.put("credits", lane == Lane.COMPILE ? 0 : adaptiveWorldgen.credits(lane));
        out.put("ewmaNanos", lane == Lane.COMPILE ? 0L : adaptiveWorldgen.ewmaNanos(lane));
        out.put("spatialDeferred", lane == Lane.COMPILE ? 0L : adaptiveWorldgen.spatialDeferred(lane));
        out.put("creditDeferred", lane == Lane.COMPILE ? 0L : adaptiveWorldgen.creditDeferred(lane));
        return out;
    }

    private static Map<String, Object> governorSnapshot() {
        ConfigSnapshot config = configSnapshot;
        long worldgenPressure = worldgenPressure();
        long commitBacklog = commitBacklog();
        long mailboxBacklog = mailboxBacklog();
        double heapUsedRatio = heapUsedRatio();
        updateMax(MAX_WORLDGEN_PRESSURE, worldgenPressure);
        updateMax(MAX_COMMIT_BACKLOG, commitBacklog);
        updateMax(MAX_MAILBOX_BACKLOG, mailboxBacklog);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuTarget", config.cpuTarget());
        out.put("worldgenWorkers", config.worldgenWorkers());
        out.put("worldgenPressureTarget", worldgenPressureTarget(config));
        out.put("worldgenPressure", worldgenPressure);
        out.put("maxWorldgenPressure", MAX_WORLDGEN_PRESSURE.get());
        out.put("worldgenActiveLimit", worldgenActiveLimit(config));
        out.put("compileActiveLimit", compileActiveLimit(config, worldgenPressure));
        out.put("compileGovernorRunning", COMPILE_GOVERNOR_RUNNING.get());
        out.put("compileThrottleEnabled", config.compileWorkers() > 1);
        out.put("commitBacklog", commitBacklog);
        out.put("maxCommitBacklog", MAX_COMMIT_BACKLOG.get());
        out.put("commitBacklogThrottleThreshold", config.commitBacklogThrottleThreshold());
        out.put("mailboxBacklog", mailboxBacklog);
        out.put("maxMailboxBacklog", MAX_MAILBOX_BACKLOG.get());
        out.put("mailboxBacklogThrottleThreshold", config.mailboxBacklogThrottleThreshold());
        out.put("heapUsedRatio", heapUsedRatio);
        out.put("heapPressureTarget", config.heapPressureTarget());
        out.put("bottleneckThrottleActive", bottleneckThrottleActive(config, commitBacklog, mailboxBacklog, heapUsedRatio));
        out.put("bottleneckThrottles", BOTTLENECK_THROTTLES.get());
        return out;
    }

    private static <T> CompletableFuture<T> supplyOnForkJoin(Lane lane, Supplier<T> task) {
        int index = lane.ordinal();
        ForkJoinPool pool = forkJoinPool(lane);
        updateMax(MAX_QUEUED, index, queuedTaskEstimate(lane));
        int maxQueuedTasks = configSnapshot.maxQueuedTasks();
        boolean reservedQueueSlot = false;
        if (maxQueuedTasks > 0) {
            reservedQueueSlot = tryReserveQueueSlot(lane, maxQueuedTasks);
            if (!reservedQueueSlot) {
                ADMISSION_REJECTED.incrementAndGet(index);
                if (lane.canInlineWhenBacklogged()) {
                    INLINE_RUNS.incrementAndGet(index);
                    try {
                        return CompletableFuture.completedFuture(runGoverned(lane, task));
                    } catch (Throwable throwable) {
                        return failedFuture(throwable);
                    }
                }
                FAILED.incrementAndGet(index);
                return failedFuture(queueFull(lane, maxQueuedTasks));
            }
        }
        ADMISSION_ACCEPTED.incrementAndGet(index);
        AtomicBoolean queueSlotHeld = reservedQueueSlot ? new AtomicBoolean(true) : null;
        try {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.whenComplete((ignored, throwable) -> {
                if (future.isCancelled()) {
                    releaseQueueSlotIfHeld(lane, queueSlotHeld);
                }
            });
            pool.execute(() -> {
                releaseQueueSlotIfHeld(lane, queueSlotHeld);
                if (!future.isCancelled()) {
                    completeFromSupplier(future, () -> runGoverned(lane, task));
                }
            });
            return future;
        } catch (RejectedExecutionException rejected) {
            releaseQueueSlotIfHeld(lane, queueSlotHeld);
            ADMISSION_REJECTED.incrementAndGet(index);
            FAILED.incrementAndGet(index);
            return failedFuture(rejected);
        }
    }

    private static <T> CompletableFuture<T> supplyNestedOnForkJoin(Lane lane, Supplier<T> task) {
        int index = lane.ordinal();
        ADMISSION_ACCEPTED.incrementAndGet(index);
        ForkJoinPool pool = forkJoinPool(lane);
        updateMax(MAX_QUEUED, index, queuedTaskEstimate(lane));
        try {
            CompletableFuture<T> future = new CompletableFuture<>();
            pool.execute(() -> completeFromSupplier(future, () -> runMeasured(lane, task)));
            return future;
        } catch (RejectedExecutionException rejected) {
            ADMISSION_REJECTED.incrementAndGet(index);
            FAILED.incrementAndGet(index);
            return failedFuture(rejected);
        }
    }

    private static void executeOnForkJoin(Lane lane, Supplier<Void> task, Consumer<Throwable> failureHandler) {
        int index = lane.ordinal();
        ForkJoinPool pool = forkJoinPool(lane);
        updateMax(MAX_QUEUED, index, queuedTaskEstimate(lane));
        int maxQueuedTasks = configSnapshot.maxQueuedTasks();
        boolean reservedQueueSlot = false;
        if (maxQueuedTasks > 0) {
            reservedQueueSlot = tryReserveQueueSlot(lane, maxQueuedTasks);
            if (!reservedQueueSlot) {
                ADMISSION_REJECTED.incrementAndGet(index);
                if (lane.canInlineWhenBacklogged()) {
                    INLINE_RUNS.incrementAndGet(index);
                    runGovernedWithFailureCallback(lane, task, failureHandler);
                    return;
                }
                FAILED.incrementAndGet(index);
                notifyFailure(failureHandler, queueFull(lane, maxQueuedTasks));
                return;
            }
        }
        ADMISSION_ACCEPTED.incrementAndGet(index);
        AtomicBoolean queueSlotHeld = reservedQueueSlot ? new AtomicBoolean(true) : null;
        try {
            pool.execute(() -> {
                releaseQueueSlotIfHeld(lane, queueSlotHeld);
                runGovernedWithFailureCallback(lane, task, failureHandler);
            });
        } catch (RejectedExecutionException rejected) {
            releaseQueueSlotIfHeld(lane, queueSlotHeld);
            ADMISSION_REJECTED.incrementAndGet(index);
            FAILED.incrementAndGet(index);
            notifyFailure(failureHandler, rejected);
        }
    }

    private static void executeNestedOnForkJoin(Lane lane, Supplier<Void> task, Consumer<Throwable> failureHandler) {
        int index = lane.ordinal();
        ADMISSION_ACCEPTED.incrementAndGet(index);
        ForkJoinPool pool = forkJoinPool(lane);
        updateMax(MAX_QUEUED, index, queuedTaskEstimate(lane));
        try {
            pool.execute(() -> runMeasuredWithFailureCallback(lane, task, failureHandler));
        } catch (RejectedExecutionException rejected) {
            ADMISSION_REJECTED.incrementAndGet(index);
            FAILED.incrementAndGet(index);
            notifyFailure(failureHandler, rejected);
        }
    }

    private static <T> T runGoverned(Lane lane, Supplier<T> supplier) {
        if (lane == Lane.COMPILE) {
            return runCompileGoverned(supplier);
        }
        return runMeasured(lane, supplier);
    }

    private static <T> Supplier<T> wrapWorkspaceContext(Supplier<T> supplier) {
        GAChunkWorkspace capturedWorkspace = GAChunkWorkspaceContext.current();
        if (capturedWorkspace == null) {
            return supplier;
        }
        return () -> {
            try (GAChunkWorkspaceContext.Scope ignored = GAChunkWorkspaceContext.bind(capturedWorkspace)) {
                return supplier.get();
            }
        };
    }

    private static <T> T runCompileGoverned(Supplier<T> supplier) {
        long waitStart = 0L;
        boolean throttled = false;
        for (;;) {
            ConfigSnapshot config = configSnapshot;
            long pressure = worldgenPressure();
            updateMax(MAX_WORLDGEN_PRESSURE, pressure);
            int limit = compileActiveLimit(config, pressure);
            int current = COMPILE_GOVERNOR_RUNNING.get();
            if (current < limit && COMPILE_GOVERNOR_RUNNING.compareAndSet(current, current + 1)) {
                break;
            }
            if (!throttled) {
                throttled = true;
                waitStart = System.nanoTime();
                GOVERNOR_THROTTLED.incrementAndGet(Lane.COMPILE.ordinal());
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("GA scheduler compile lane interrupted while throttled by governor");
            }
        }

        try {
            if (throttled) {
                long waited = System.nanoTime() - waitStart;
                GOVERNOR_WAIT_NANOS.addAndGet(Lane.COMPILE.ordinal(), waited);
                GAWallTimeTelemetry.addElapsed(GAWallTimeTelemetry.Stage.SCHEDULER_WAIT_IDLE, waited);
            }
            return runMeasured(Lane.COMPILE, supplier);
        } finally {
            COMPILE_GOVERNOR_RUNNING.decrementAndGet();
        }
    }

    private static <T> T runMeasured(Lane lane, Supplier<T> supplier) {
        int index = lane.ordinal();
        int active = ACTIVE[index].incrementAndGet();
        updateMax(MAX_ACTIVE, index, active);
        long start = System.nanoTime();
        try {
            T result = supplier.get();
            COMPLETED.incrementAndGet(index);
            return result;
        } catch (RuntimeException | Error throwable) {
            FAILED.incrementAndGet(index);
            throw throwable;
        } finally {
            EXECUTION_NANOS.addAndGet(index, System.nanoTime() - start);
            ACTIVE[index].decrementAndGet();
        }
    }

    private static void runMeasuredWithFailureCallback(Lane lane, Supplier<Void> task, Consumer<Throwable> failureHandler) {
        try {
            runMeasured(lane, task);
        } catch (Throwable throwable) {
            notifyFailure(failureHandler, throwable);
        }
    }

    private static void runGovernedWithFailureCallback(Lane lane, Supplier<Void> task, Consumer<Throwable> failureHandler) {
        try {
            runGoverned(lane, task);
        } catch (Throwable throwable) {
            notifyFailure(failureHandler, throwable);
        }
    }

    private static void ensureInitialized() {
        if (!initialized) {
            init(false);
        }
    }

    private static ForkJoinPool newPool(Lane lane, int workers) {
        int parallelism = Math.max(1, Math.min(0x7fff, workers));
        AtomicInteger counter = new AtomicInteger();
        return new ForkJoinPool(
                parallelism,
                pool -> {
                    ForkJoinWorkerThread worker = new GAForkJoinWorkerThread(pool);
                    worker.setName("GA-" + lane.name() + "-" + counter.incrementAndGet());
                    return worker;
                },
                (thread, throwable) -> GeneratorAccelerator.LOGGER.warn(
                        "GA scheduler worker {} failed", thread.getName(), throwable),
                false
        );
    }

    private static long queuedTaskEstimate(Lane lane) {
        if (lane != Lane.COMPILE && adaptiveWorldgen != null) {
            return Math.max(adaptiveWorldgen.queued(lane), ADMITTED_QUEUED[lane.ordinal()].get());
        }
        ForkJoinPool pool = existingPool(lane);
        long forkJoinEstimate = pool == null ? 0L : pool.getQueuedTaskCount() + pool.getQueuedSubmissionCount();
        return Math.max(forkJoinEstimate, ADMITTED_QUEUED[lane.ordinal()].get());
    }

    private static ForkJoinPool existingPool(Lane lane) {
        return switch (lane) {
            case NOISE -> noisePool;
            case COMPILE -> compilePool;
            case WORKSPACE -> workspacePool;
            case TRANSACTIONAL -> transactionalPool;
            case SERIAL -> serialPool;
            case COMMIT -> commitPool;
        };
    }

    private static void setPool(Lane lane, ForkJoinPool pool) {
        switch (lane) {
            case NOISE -> noisePool = pool;
            case COMPILE -> compilePool = pool;
            case WORKSPACE -> workspacePool = pool;
            case TRANSACTIONAL -> transactionalPool = pool;
            case SERIAL -> serialPool = pool;
            case COMMIT -> commitPool = pool;
        }
    }

    private static int workersFor(Lane lane, ConfigSnapshot config) {
        return switch (lane) {
            case NOISE -> config.noiseWorkers();
            case COMPILE -> config.compileWorkers();
            case WORKSPACE -> config.workspaceWorkers();
            case TRANSACTIONAL -> config.transactionalWorkers();
            case SERIAL -> config.serialWorkers();
            case COMMIT -> config.commitWorkers();
        };
    }

    private static long worldgenPressure() {
        GAAdaptiveWorldgenScheduler scheduler = adaptiveWorldgen;
        if (scheduler != null) {
            return scheduler.worldgenPressure();
        }
        long pressure = 0L;
        for (Lane lane : Lane.worldgenPressureLanes()) {
            int index = lane.ordinal();
            pressure += ACTIVE[index].get();
            pressure += ADMITTED_QUEUED[index].get();
        }
        return pressure;
    }

    private static int compileActiveLimit(ConfigSnapshot config, long worldgenPressure) {
        if (worldgenPressure < worldgenPressureTarget(config)) {
            return Integer.MAX_VALUE;
        }
        return 1;
    }

    private static int worldgenPressureTarget(ConfigSnapshot config) {
        return Math.max(1, (int) Math.ceil(config.worldgenWorkers() * config.cpuTarget()));
    }

    private static int worldgenActiveLimit(ConfigSnapshot config) {
        return worldgenActiveLimit(config, bottleneckThrottleActive(config, commitBacklog(), mailboxBacklog(), heapUsedRatio()));
    }

    private static int worldgenActiveLimit(ConfigSnapshot config, boolean bottleneckActive) {
        int cpuLimit = Math.max(1, (int) Math.ceil(config.worldgenWorkers() * config.cpuTarget()));
        if (!bottleneckActive) {
            return cpuLimit;
        }
        return Math.max(1, Math.min(cpuLimit, config.bottleneckActiveLimit()));
    }

    private static boolean bottleneckThrottleActive(
            ConfigSnapshot config,
            long commitBacklog,
            long mailboxBacklog,
            double heapUsedRatio
    ) {
        return (config.commitBacklogThrottleThreshold() > 0 && commitBacklog >= config.commitBacklogThrottleThreshold())
                || (config.mailboxBacklogThrottleThreshold() > 0 && mailboxBacklog >= config.mailboxBacklogThrottleThreshold())
                || (config.heapPressureTarget() > 0.0D && heapUsedRatio >= config.heapPressureTarget());
    }

    private static long commitBacklog() {
        if (adaptiveWorldgen != null) {
            return ACTIVE[Lane.COMMIT.ordinal()].get() + adaptiveWorldgen.queued(Lane.COMMIT);
        }
        return ACTIVE[Lane.COMMIT.ordinal()].get() + queuedTaskEstimate(Lane.COMMIT);
    }

    private static long mailboxBacklog() {
        return GACrossChunkMailboxRuntime.queuedCommands();
    }

    private static double heapUsedRatio() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max <= 0L || max == Long.MAX_VALUE) {
            return 0.0D;
        }
        long used = runtime.totalMemory() - runtime.freeMemory();
        return Math.max(0.0D, Math.min(1.0D, (double) used / (double) max));
    }

    private static final AtomicBoolean QUEUE_REJECTED = new AtomicBoolean(false);

    private static AtomicBoolean reserveQueueSlotOrReject(Lane lane, CompletableFuture<?> future) {
        int index = lane.ordinal();
        int maxQueuedTasks = configSnapshot.maxQueuedTasks();
        boolean reservedQueueSlot = false;
        if (maxQueuedTasks > 0) {
            reservedQueueSlot = tryReserveQueueSlot(lane, maxQueuedTasks);
            if (!reservedQueueSlot) {
                ADMISSION_REJECTED.incrementAndGet(index);
                FAILED.incrementAndGet(index);
                future.completeExceptionally(queueFull(lane, maxQueuedTasks));
                return QUEUE_REJECTED;
            }
        }
        ADMISSION_ACCEPTED.incrementAndGet(index);
        return reservedQueueSlot ? new AtomicBoolean(true) : null;
    }

    private static AtomicBoolean reserveQueueSlotOrNotify(Lane lane, Consumer<Throwable> failureHandler) {
        int index = lane.ordinal();
        int maxQueuedTasks = configSnapshot.maxQueuedTasks();
        boolean reservedQueueSlot = false;
        if (maxQueuedTasks > 0) {
            reservedQueueSlot = tryReserveQueueSlot(lane, maxQueuedTasks);
            if (!reservedQueueSlot) {
                ADMISSION_REJECTED.incrementAndGet(index);
                FAILED.incrementAndGet(index);
                notifyFailure(failureHandler, queueFull(lane, maxQueuedTasks));
                return QUEUE_REJECTED;
            }
        }
        ADMISSION_ACCEPTED.incrementAndGet(index);
        return reservedQueueSlot ? new AtomicBoolean(true) : null;
    }

    private static RejectedExecutionException queueFull(Lane lane, int maxQueuedTasks) {
        return new RejectedExecutionException(
                "GA scheduler lane " + lane.jsonName() + " queue is full: "
                        + ADMITTED_QUEUED[lane.ordinal()].get() + " >= " + maxQueuedTasks
        );
    }

    private static boolean tryReserveQueueSlot(Lane lane, int maxQueuedTasks) {
        int index = lane.ordinal();
        AtomicInteger queued = ADMITTED_QUEUED[index];
        int current;
        do {
            current = queued.get();
            if (current >= maxQueuedTasks) {
                return false;
            }
        } while (!queued.compareAndSet(current, current + 1));
        updateMax(MAX_QUEUED, index, current + 1L);
        return true;
    }

    private static void releaseQueueSlot(Lane lane) {
        ADMITTED_QUEUED[lane.ordinal()].updateAndGet(value -> Math.max(0, value - 1));
    }

    private static void releaseQueueSlotIfHeld(Lane lane, AtomicBoolean queueSlotHeld) {
        if (queueSlotHeld != null && queueSlotHeld != QUEUE_REJECTED && queueSlotHeld.compareAndSet(true, false)) {
            releaseQueueSlot(lane);
        }
    }

    private static void resetAdmissionForTests() {
        for (AtomicInteger queued : ADMITTED_QUEUED) {
            queued.set(0);
        }
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(throwable);
        return failed;
    }

    private static <T> void completeFromSupplier(CompletableFuture<T> future, Supplier<T> supplier) {
        try {
            future.complete(supplier.get());
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
    }

    private static void notifyFailure(Consumer<Throwable> failureHandler, Throwable throwable) {
        if (failureHandler == null) {
            GeneratorAccelerator.LOGGER.warn("GA scheduler async task failed", throwable);
            return;
        }
        try {
            failureHandler.accept(throwable);
        } catch (Throwable handlerFailure) {
            throwable.addSuppressed(handlerFailure);
            GeneratorAccelerator.LOGGER.warn("GA scheduler async failure handler failed", throwable);
        }
    }

    private static void shutdownPool(ForkJoinPool pool) {
        if (pool == null) {
            return;
        }
        pool.shutdownNow();
        try {
            pool.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isCurrentLaneWorker(Lane lane) {
        GAScheduledTask current = CURRENT_TASK.get();
        if (current != null) {
            return current.lane == lane;
        }
        return lane == Lane.COMPILE && Thread.currentThread().getName().startsWith("GA-COMPILE-");
    }

    private static boolean isCurrentGaWorker() {
        return CURRENT_TASK.get() != null || Thread.currentThread().getName().startsWith("GA-");
    }

    private static boolean shouldInlineNestedFromGaWorker(Lane lane) {
        if (lane == Lane.COMPILE || !lane.isAdaptiveWorldgen()) {
            return false;
        }
        GAScheduledTask current = CURRENT_TASK.get();
        if (current == null) {
            return false;
        }
        if (current.region != null) {
            return true;
        }
        ConfigSnapshot config = configSnapshot;
        long commitBacklog = commitBacklog();
        long mailboxBacklog = mailboxBacklog();
        double heapUsedRatio = heapUsedRatio();
        boolean bottleneckActive = bottleneckThrottleActive(config, commitBacklog, mailboxBacklog, heapUsedRatio);
        return adaptiveWorldgen.activeWorkers() >= worldgenActiveLimit(config, bottleneckActive);
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

    private static void updateMax(AtomicLong value, long next) {
        long current;
        do {
            current = value.get();
            if (next <= current) {
                return;
            }
        } while (!value.compareAndSet(current, next));
    }

    private static int nextPowerOfTwo(int value) {
        int highest = Integer.highestOneBit(value);
        if (highest == value) {
            return value;
        }
        return highest >= (1 << 30) ? 1 << 30 : highest << 1;
    }

    private static int stripe(int chunkX, int chunkZ, int mask) {
        long x = chunkX * 0x9E3779B97F4A7C15L;
        long z = chunkZ * 0xC2B2AE3D27D4EB4FL;
        long h = x ^ Long.rotateLeft(z, 31);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) h & mask;
    }

    public enum Lane {
        NOISE,
        COMPILE,
        WORKSPACE,
        TRANSACTIONAL,
        SERIAL,
        COMMIT;

        public String jsonName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        boolean canInlineWhenBacklogged() {
            return this == COMPILE;
        }

        boolean isAdaptiveWorldgen() {
            return this != COMPILE;
        }

        static Lane[] worldgenPressureLanes() {
            return new Lane[]{NOISE, WORKSPACE, TRANSACTIONAL, SERIAL, COMMIT};
        }
    }

    public record ConflictRegion(int centerX, int centerZ, int radius, int[] stripes) {
        private static ConflictRegion of(int centerX, int centerZ, int radius, int stripeCount) {
            int safeRadius = Math.max(0, radius);
            int safeStripeCount = nextPowerOfTwo(Math.max(1024, stripeCount));
            int mask = safeStripeCount - 1;
            int[] values = new int[(safeRadius * 2 + 1) * (safeRadius * 2 + 1)];
            int size = 0;
            for (int dz = -safeRadius; dz <= safeRadius; dz++) {
                for (int dx = -safeRadius; dx <= safeRadius; dx++) {
                    int stripe = stripe(centerX + dx, centerZ + dz, mask);
                    boolean duplicate = false;
                    for (int i = 0; i < size; i++) {
                        if (values[i] == stripe) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        values[size++] = stripe;
                    }
                }
            }
            int[] compact = Arrays.copyOf(values, size);
            Arrays.sort(compact);
            return new ConflictRegion(centerX, centerZ, safeRadius, compact);
        }
    }

    private static final class GAScheduledTask {
        private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();

        private final Lane lane;
        private final int priority;
        private final long sequence;
        private final ConflictRegion region;
        private final boolean nested;
        private final AtomicBoolean queueSlotHeld;
        private final Runnable runnable;
        private final Consumer<Throwable> failureHandler;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean regionRetained = new AtomicBoolean();
        private final AtomicBoolean regionReleased = new AtomicBoolean();
        private GAAdaptiveWorldgenScheduler owner;

        private GAScheduledTask(
                Lane lane,
                int priority,
                ConflictRegion region,
                boolean nested,
                AtomicBoolean queueSlotHeld,
                Runnable runnable,
                Consumer<Throwable> failureHandler
        ) {
            this.lane = lane;
            this.priority = priority;
            this.sequence = NEXT_SEQUENCE.getAndIncrement();
            this.region = region;
            this.nested = nested;
            this.queueSlotHeld = queueSlotHeld;
            this.runnable = runnable;
            this.failureHandler = failureHandler;
        }

        private void cancel() {
            if (!this.cancelled.compareAndSet(false, true)) {
                return;
            }
            releaseQueueSlotIfHeld(this.lane, this.queueSlotHeld);
            GAAdaptiveWorldgenScheduler scheduler = this.owner;
            if (scheduler != null) {
                scheduler.cancelQueued(this);
            }
        }

        private void run() {
            if (this.cancelled.get()) {
                return;
            }
            try {
                this.runnable.run();
            } catch (Throwable throwable) {
                notifyFailure(this.failureHandler, throwable);
            }
        }

        private void releaseRetainedRegion() {
            GAAdaptiveWorldgenScheduler scheduler = this.owner;
            if (scheduler != null && this.region != null && this.regionReleased.compareAndSet(false, true)) {
                scheduler.releaseRegion(this);
            }
        }
    }

    private static final class GALaneState {
        private static final long EWMA_UNINITIALIZED = -1L;
        private final Lane lane;
        private final int cap;
        private long queued;
        private int active;
        private int credits;
        private long ewmaNanos = EWMA_UNINITIALIZED;
        private long spatialDeferred;
        private long creditDeferred;

        private GALaneState(Lane lane, int cap) {
            this.lane = lane;
            this.cap = Math.max(1, cap);
        }

        private void recordElapsed(long nanos) {
            long safe = Math.max(0L, nanos);
            if (this.ewmaNanos == EWMA_UNINITIALIZED) {
                this.ewmaNanos = safe;
                return;
            }
            this.ewmaNanos = ((this.ewmaNanos * 7L) + safe) >>> 3;
        }

        private long visibleEwma() {
            return this.ewmaNanos == EWMA_UNINITIALIZED ? defaultCostNanos(this.lane) : this.ewmaNanos;
        }

        private static long defaultCostNanos(Lane lane) {
            return switch (lane) {
                case NOISE -> TimeUnit.MILLISECONDS.toNanos(20L);
                case TRANSACTIONAL -> TimeUnit.MILLISECONDS.toNanos(8L);
                case WORKSPACE -> TimeUnit.MILLISECONDS.toNanos(4L);
                case SERIAL, COMMIT -> TimeUnit.MILLISECONDS.toNanos(2L);
                case COMPILE -> TimeUnit.MILLISECONDS.toNanos(1L);
            };
        }
    }

    private static final class GASpatialConflictMap {
        private final long[] ownerByStripe;
        private long nextOwner = 1L;
        private int activeRegions;
        private int maxActiveRegions;
        private long deferredTasks;

        private GASpatialConflictMap(int stripeCount) {
            this.ownerByStripe = new long[nextPowerOfTwo(Math.max(1024, stripeCount))];
        }

        private boolean canAcquire(ConflictRegion region) {
            if (region == null) {
                return true;
            }
            for (int stripe : region.stripes()) {
                if (ownerByStripe[stripe] != 0L) {
                    deferredTasks++;
                    return false;
                }
            }
            return true;
        }

        private void acquire(GAScheduledTask task) {
            ConflictRegion region = task.region;
            if (region == null) {
                return;
            }
            long owner = nextOwner++;
            if (owner == 0L) {
                owner = nextOwner++;
            }
            for (int stripe : region.stripes()) {
                ownerByStripe[stripe] = owner;
            }
            activeRegions++;
            maxActiveRegions = Math.max(maxActiveRegions, activeRegions);
        }

        private void release(GAScheduledTask task) {
            ConflictRegion region = task.region;
            if (region == null) {
                return;
            }
            for (int stripe : region.stripes()) {
                ownerByStripe[stripe] = 0L;
            }
            activeRegions = Math.max(0, activeRegions - 1);
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stripeCount", ownerByStripe.length);
            out.put("activeRegions", activeRegions);
            out.put("maxActiveRegions", maxActiveRegions);
            out.put("deferredTasks", deferredTasks);
            return out;
        }

        private void resetMetrics() {
            maxActiveRegions = activeRegions;
            deferredTasks = 0L;
        }
    }

    private static final class GAAdaptiveWorldgenScheduler {
        private static final long CREDIT_RECALC_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);

        private final ConfigSnapshot config;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition workAvailable = lock.newCondition();
        private final ArrayList<GAScheduledTask> queue = new ArrayList<>();
        private final GALaneState[] lanes = new GALaneState[Lane.values().length];
        private final GASpatialConflictMap spatial;
        private final AdaptiveWorker[] workers;
        private final AtomicInteger activeWorkers = new AtomicInteger();
        private final AtomicInteger runningWorkers = new AtomicInteger();
        private final AtomicInteger parkedWorkers = new AtomicInteger();
        private final AtomicLong dispatchWakeups = new AtomicLong();
        private volatile boolean shutdown;
        private long lastCreditRecalcNanos;

        private GAAdaptiveWorldgenScheduler(ConfigSnapshot config) {
            this.config = config;
            this.spatial = new GASpatialConflictMap(config.spatialConflictStripes());
            for (Lane lane : Lane.values()) {
                if (lane == Lane.COMPILE) {
                    continue;
                }
                lanes[lane.ordinal()] = new GALaneState(lane, switch (lane) {
                    case NOISE -> config.noiseWorkers();
                    case WORKSPACE -> config.workspaceWorkers();
                    case TRANSACTIONAL -> config.transactionalWorkers();
                    case SERIAL -> 1;
                    case COMMIT -> 1;
                    case COMPILE -> 1;
                });
            }
            this.workers = new AdaptiveWorker[config.worldgenWorkers()];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new AdaptiveWorker(this, i + 1);
                workers[i].start();
            }
        }

        private void submit(GAScheduledTask task) {
            task.owner = this;
            lock.lock();
            try {
                if (shutdown) {
                    releaseQueueSlotIfHeld(task.lane, task.queueSlotHeld);
                    notifyFailure(task.failureHandler, new RejectedExecutionException("GA adaptive worldgen scheduler is shut down"));
                    return;
                }
                GALaneState state = laneState(task.lane);
                state.queued++;
                queue.add(task);
                updateMax(MAX_QUEUED, task.lane.ordinal(), state.queued);
                recalculateCredits(true);
                signal();
            } finally {
                lock.unlock();
            }
        }

        private GAScheduledTask takeTask() throws InterruptedException {
            lock.lock();
            try {
                for (;;) {
                    if (shutdown && queue.isEmpty()) {
                        return null;
                    }
                    recalculateCredits(false);
                    GAScheduledTask task = selectTask();
                    if (task != null) {
                        GALaneState state = laneState(task.lane);
                        queue.remove(task);
                        state.queued = Math.max(0L, state.queued - 1L);
                        state.active++;
                        activeWorkers.incrementAndGet();
                        releaseQueueSlotIfHeld(task.lane, task.queueSlotHeld);
                        if (task.region != null) {
                            spatial.acquire(task);
                        }
                        return task;
                    }
                    parkedWorkers.incrementAndGet();
                    try {
                        workAvailable.await(CREDIT_RECALC_NANOS, TimeUnit.NANOSECONDS);
                    } finally {
                        parkedWorkers.decrementAndGet();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private GAScheduledTask selectTask() {
            int activeTarget = activeTarget();
            if (activeWorkers.get() >= activeTarget) {
                return null;
            }
            GAScheduledTask best = null;
            for (GAScheduledTask task : queue) {
                if (task.cancelled.get()) {
                    continue;
                }
                GALaneState state = laneState(task.lane);
                if (state.active >= state.cap) {
                    state.creditDeferred++;
                    continue;
                }
                if (!task.nested && state.active >= state.credits) {
                    state.creditDeferred++;
                    continue;
                }
                if (!spatial.canAcquire(task.region)) {
                    state.spatialDeferred++;
                    continue;
                }
                if (best == null
                        || task.priority > best.priority
                        || (task.priority == best.priority && task.sequence < best.sequence)) {
                    best = task;
                }
            }
            return best;
        }

        private void complete(GAScheduledTask task, long elapsedNanos) {
            lock.lock();
            try {
                GALaneState state = laneState(task.lane);
                state.active = Math.max(0, state.active - 1);
                state.recordElapsed(elapsedNanos);
                activeWorkers.updateAndGet(value -> Math.max(0, value - 1));
                if (task.region != null && !task.regionRetained.get() && task.regionReleased.compareAndSet(false, true)) {
                    spatial.release(task);
                }
                recalculateCredits(true);
                signal();
            } finally {
                lock.unlock();
            }
        }

        private void releaseRegion(GAScheduledTask task) {
            lock.lock();
            try {
                spatial.release(task);
                signal();
            } finally {
                lock.unlock();
            }
        }

        private void cancelQueued(GAScheduledTask task) {
            lock.lock();
            try {
                if (queue.remove(task)) {
                    GALaneState state = laneState(task.lane);
                    state.queued = Math.max(0L, state.queued - 1L);
                    recalculateCredits(true);
                }
                signal();
            } finally {
                lock.unlock();
            }
        }

        private void recalculateCredits(boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastCreditRecalcNanos < CREDIT_RECALC_NANOS) {
                return;
            }
            lastCreditRecalcNanos = now;
            int target = activeTarget();
            int totalBase = 0;
            double totalScore = 0.0D;
            for (Lane lane : Lane.worldgenPressureLanes()) {
                GALaneState state = laneState(lane);
                boolean hasWork = state.queued > 0L || state.active > 0;
                int base = hasWork ? Math.min(state.cap, Math.max(1, state.active)) : 0;
                state.credits = base;
                totalBase += base;
                if (state.queued > 0L && base < state.cap) {
                    totalScore += laneScore(state);
                }
            }
            int remaining = Math.max(0, target - totalBase);
            if (remaining <= 0 || totalScore <= 0.0D) {
                return;
            }
            for (Lane lane : Lane.worldgenPressureLanes()) {
                GALaneState state = laneState(lane);
                if (state.queued <= 0L || state.credits >= state.cap) {
                    continue;
                }
                int grant = Math.max(1, (int) Math.floor((laneScore(state) / totalScore) * remaining));
                state.credits = Math.min(state.cap, state.credits + grant);
            }
        }

        private double laneScore(GALaneState state) {
            double costWeight = Math.max(1.0D, (double) state.visibleEwma() / (double) TimeUnit.MILLISECONDS.toNanos(2L));
            return Math.max(1.0D, state.queued) * costWeight;
        }

        private int activeTarget() {
            long commitBacklog = ACTIVE[Lane.COMMIT.ordinal()].get() + queued(Lane.COMMIT);
            long mailboxBacklog = mailboxBacklog();
            double heapUsedRatio = heapUsedRatio();
            updateMax(MAX_COMMIT_BACKLOG, commitBacklog);
            updateMax(MAX_MAILBOX_BACKLOG, mailboxBacklog);
            boolean bottleneckActive = bottleneckThrottleActive(config, commitBacklog, mailboxBacklog, heapUsedRatio);
            if (bottleneckActive) {
                BOTTLENECK_THROTTLES.incrementAndGet();
            }
            return worldgenActiveLimit(config, bottleneckActive);
        }

        private GALaneState laneState(Lane lane) {
            GALaneState state = lanes[lane.ordinal()];
            if (state == null) {
                throw new IllegalArgumentException("Compile lane is not part of adaptive worldgen scheduler");
            }
            return state;
        }

        private void signal() {
            dispatchWakeups.incrementAndGet();
            if (lock.isHeldByCurrentThread()) {
                workAvailable.signalAll();
                return;
            }
            lock.lock();
            try {
                workAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void shutdown() {
            ArrayList<GAScheduledTask> pending;
            lock.lock();
            try {
                shutdown = true;
                pending = new ArrayList<>(queue);
                queue.clear();
                for (Lane lane : Lane.worldgenPressureLanes()) {
                    laneState(lane).queued = 0L;
                }
                signal();
            } finally {
                lock.unlock();
            }
            RejectedExecutionException shutdownFailure =
                    new RejectedExecutionException("GA adaptive worldgen scheduler is shut down");
            for (GAScheduledTask task : pending) {
                task.cancelled.set(true);
                releaseQueueSlotIfHeld(task.lane, task.queueSlotHeld);
                notifyFailure(task.failureHandler, shutdownFailure);
            }
            for (AdaptiveWorker worker : workers) {
                worker.interrupt();
            }
            for (AdaptiveWorker worker : workers) {
                try {
                    worker.join(TimeUnit.SECONDS.toMillis(5L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private Map<String, Object> snapshot() {
            lock.lock();
            try {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("adaptiveEnabled", true);
                out.put("worldgenWorkers", config.worldgenWorkers());
                out.put("activeWorkers", activeWorkers.get());
                out.put("runningWorkers", runningWorkers.get());
                out.put("parkedWorkers", parkedWorkers.get());
                out.put("queuedTasks", queue.size());
                out.put("dispatchWakeups", dispatchWakeups.get());
                out.put("activeTarget", activeTarget());
                return out;
            } finally {
                lock.unlock();
            }
        }

        private Map<String, Object> spatialSnapshot() {
            lock.lock();
            try {
                return spatial.snapshot();
            } finally {
                lock.unlock();
            }
        }

        private void resetMetrics() {
            lock.lock();
            try {
                dispatchWakeups.set(0L);
                spatial.resetMetrics();
                for (Lane lane : Lane.worldgenPressureLanes()) {
                    GALaneState state = laneState(lane);
                    state.spatialDeferred = 0L;
                    state.creditDeferred = 0L;
                }
            } finally {
                lock.unlock();
            }
        }

        private int activeWorkers() {
            return activeWorkers.get();
        }

        private int runningWorkers() {
            return runningWorkers.get();
        }

        private long queued(Lane lane) {
            if (lane == Lane.COMPILE) {
                return 0L;
            }
            lock.lock();
            try {
                return laneState(lane).queued;
            } finally {
                lock.unlock();
            }
        }

        private long totalQueued() {
            lock.lock();
            try {
                return queue.size();
            } finally {
                lock.unlock();
            }
        }

        private int credits(Lane lane) {
            if (lane == Lane.COMPILE) {
                return 0;
            }
            lock.lock();
            try {
                return laneState(lane).credits;
            } finally {
                lock.unlock();
            }
        }

        private long ewmaNanos(Lane lane) {
            if (lane == Lane.COMPILE) {
                return 0L;
            }
            lock.lock();
            try {
                return laneState(lane).visibleEwma();
            } finally {
                lock.unlock();
            }
        }

        private long spatialDeferred(Lane lane) {
            if (lane == Lane.COMPILE) {
                return 0L;
            }
            lock.lock();
            try {
                return laneState(lane).spatialDeferred;
            } finally {
                lock.unlock();
            }
        }

        private long creditDeferred(Lane lane) {
            if (lane == Lane.COMPILE) {
                return 0L;
            }
            lock.lock();
            try {
                return laneState(lane).creditDeferred;
            } finally {
                lock.unlock();
            }
        }

        private long worldgenPressure() {
            lock.lock();
            try {
                long pressure = activeWorkers.get();
                for (Lane lane : Lane.worldgenPressureLanes()) {
                    pressure += laneState(lane).queued;
                }
                return pressure;
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class AdaptiveWorker extends Thread implements GAFastLocalHolder {
        private final GAAdaptiveWorldgenScheduler scheduler;
        private final Object[] fastLocals = new Object[GAThread.FAST_THREAD_LOCAL_SIZE];

        private AdaptiveWorker(GAAdaptiveWorldgenScheduler scheduler, int index) {
            super("GA-WORLDGEN-" + index);
            this.scheduler = scheduler;
            setDaemon(true);
        }

        @Override
        public Object[] gaFastLocals() {
            return fastLocals;
        }

        @Override
        public void run() {
            scheduler.runningWorkers.incrementAndGet();
            try {
                for (;;) {
                    GAScheduledTask task;
                    try {
                        task = scheduler.takeTask();
                    } catch (InterruptedException interrupted) {
                        if (scheduler.shutdown) {
                            return;
                        }
                        continue;
                    }
                    if (task == null) {
                        return;
                    }
                    CURRENT_TASK.set(task);
                    long start = System.nanoTime();
                    try {
                        task.run();
                    } finally {
                        CURRENT_TASK.remove();
                        scheduler.complete(task, System.nanoTime() - start);
                    }
                }
            } finally {
                scheduler.runningWorkers.decrementAndGet();
            }
        }
    }

    private record ConfigSnapshot(
            int worldgenWorkers,
            int noiseWorkers,
            int compileWorkers,
            int workspaceWorkers,
            int transactionalWorkers,
            int serialWorkers,
            int commitWorkers,
            int maxQueuedTasks,
            double cpuTarget,
            int commitBacklogThrottleThreshold,
            int mailboxBacklogThrottleThreshold,
            double heapPressureTarget,
            int bottleneckActiveLimit,
            int spatialConflictStripes
    ) {
        static ConfigSnapshot defaults() {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            return from(new GAConfig(), processors, false);
        }

        static ConfigSnapshot from(GAConfig config, int processors, boolean isDev) {
            int defaultWorldgen = Math.max(1, processors - 1);
            int worldgenWorkers = positiveOrDefault(config.schedulerWorldgenWorkers, defaultWorldgen);
            int activeWorldgenBudget = Math.max(1, worldgenWorkers - 2);
            int defaultNoise = Math.max(1, Math.round(activeWorldgenBudget * 0.60F));
            int defaultWorkspace = Math.max(1, Math.round(activeWorldgenBudget * 0.30F));
            int defaultTransactional = Math.max(1, activeWorldgenBudget - defaultNoise - defaultWorkspace);
            int defaultCompile = Math.min(4, Math.max(1, processors / 3));
            return new ConfigSnapshot(
                    worldgenWorkers,
                    positiveOrDefault(config.schedulerNoiseWorkers, defaultNoise),
                    positiveOrDefault(config.schedulerCompileWorkers, defaultCompile),
                    positiveOrDefault(config.schedulerWorkspaceWorkers, defaultWorkspace),
                    positiveOrDefault(config.schedulerTransactionalWorkers, defaultTransactional),
                    1,
                    1,
                    Math.max(0, config.schedulerMaxQueuedTasks),
                    config.schedulerCpuTarget <= 0.0D ? 0.85D : Math.min(1.0D, config.schedulerCpuTarget),
                    Math.max(0, config.schedulerCommitBacklogThrottleThreshold),
                    Math.max(0, config.schedulerMailboxBacklogThrottleThreshold),
                    config.schedulerHeapPressureTarget <= 0.0D ? 0.0D : Math.min(1.0D, config.schedulerHeapPressureTarget),
                    1,
                    nextPowerOfTwo(Math.max(1024, config.chunkPipelineGuardStripes))
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("worldgenWorkers", worldgenWorkers);
            out.put("noiseWorkers", noiseWorkers);
            out.put("compileWorkers", compileWorkers);
            out.put("workspaceWorkers", workspaceWorkers);
            out.put("transactionalWorkers", transactionalWorkers);
            out.put("serialWorkers", serialWorkers);
            out.put("commitWorkers", commitWorkers);
            out.put("maxQueuedTasks", maxQueuedTasks);
            out.put("cpuTarget", cpuTarget);
            out.put("commitBacklogThrottleThreshold", commitBacklogThrottleThreshold);
            out.put("mailboxBacklogThrottleThreshold", mailboxBacklogThrottleThreshold);
            out.put("heapPressureTarget", heapPressureTarget);
            out.put("bottleneckActiveLimit", bottleneckActiveLimit);
            out.put("spatialConflictStripes", spatialConflictStripes);
            return out;
        }

        private static int positiveOrDefault(int value, int fallback) {
            return value > 0 ? value : fallback;
        }
    }
}
