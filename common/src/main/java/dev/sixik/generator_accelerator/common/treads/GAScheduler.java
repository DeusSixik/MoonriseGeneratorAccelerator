package dev.sixik.generator_accelerator.common.treads;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Bounded GA-owned worldgen executors. Minecraft/Moonrise still own chunk graph;
 * this only replaces ad-hoc/common-pool work inside GA-controlled hot paths.
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

    private static volatile boolean initialized;
    private static volatile ForkJoinPool noisePool;
    private static volatile ForkJoinPool compilePool;
    private static volatile ForkJoinPool workspacePool;
    private static volatile ForkJoinPool transactionalPool;
    private static volatile ForkJoinPool serialPool;
    private static volatile ForkJoinPool commitPool;
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
            initialized = true;
        }
    }

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

    public static <T> CompletableFuture<T> supplyAsync(Lane lane, Supplier<T> supplier) {
        ensureInitialized();
        int index = lane.ordinal();
        SUBMITTED.incrementAndGet(index);
        ForkJoinPool pool = forkJoinPool(lane);
        long queued = queuedTaskEstimate(lane, pool);
        updateMax(MAX_QUEUED, index, queued);

        int maxQueuedTasks = configSnapshot.maxQueuedTasks();
        boolean reservedQueueSlot = false;
        if (maxQueuedTasks > 0) {
            reservedQueueSlot = tryReserveQueueSlot(lane, maxQueuedTasks);
            if (!reservedQueueSlot) {
                ADMISSION_REJECTED.incrementAndGet(index);
                if (lane.canInlineWhenBacklogged()) {
                    INLINE_RUNS.incrementAndGet(index);
                    try {
                        return CompletableFuture.completedFuture(runGoverned(lane, supplier));
                    } catch (Throwable throwable) {
                        return failedFuture(throwable);
                    }
                }
                FAILED.incrementAndGet(index);
                return failedFuture(new RejectedExecutionException(
                        "GA scheduler lane " + lane.jsonName() + " queue is full: "
                                + ADMITTED_QUEUED[index].get() + " >= " + maxQueuedTasks
                ));
            }
        }
        ADMISSION_ACCEPTED.incrementAndGet(index);

        AtomicBoolean queueSlotHeld = reservedQueueSlot ? new AtomicBoolean(true) : null;
        try {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                releaseQueueSlotIfHeld(lane, queueSlotHeld);
                return runGoverned(lane, supplier);
            }, pool);
            if (queueSlotHeld != null) {
                future.whenComplete((ignored, failure) -> releaseQueueSlotIfHeld(lane, queueSlotHeld));
            }
            return future;
        } catch (RejectedExecutionException rejected) {
            releaseQueueSlotIfHeld(lane, queueSlotHeld);
            ADMISSION_REJECTED.incrementAndGet(index);
            if (lane.canInlineWhenBacklogged()) {
                INLINE_RUNS.incrementAndGet(index);
                try {
                    return CompletableFuture.completedFuture(runGoverned(lane, supplier));
                } catch (Throwable throwable) {
                    rejected.addSuppressed(throwable);
                    return failedFuture(rejected);
                }
            }
            FAILED.incrementAndGet(index);
            return failedFuture(rejected);
        }
    }

    public static void execute(Lane lane, Runnable runnable) {
        supplyAsync(lane, () -> {
            runnable.run();
            return null;
        });
    }

    public static void invokeBlocking(Lane lane, Runnable runnable) throws InterruptedException, ExecutionException {
        ensureInitialized();
        if (isCurrentLaneWorker(lane)) {
            int index = lane.ordinal();
            SUBMITTED.incrementAndGet(index);
            runMeasured(lane, () -> {
                runnable.run();
                return null;
            });
            return;
        }
        supplyAsync(lane, () -> {
            runnable.run();
            return null;
        }).get();
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
        out.put("governor", governorSnapshot());
        return out;
    }

    public static String summary() {
        ensureInitialized();
        StringBuilder builder = new StringBuilder(256);
        boolean first = true;
        for (Lane lane : Lane.values()) {
            if (!first) {
                builder.append("; ");
            }
            first = false;
            ForkJoinPool pool = existingPool(lane);
            int index = lane.ordinal();
            builder.append(lane.jsonName())
                    .append("(workers=").append(pool == null ? workersFor(lane, configSnapshot) : pool.getParallelism())
                    .append(", active=").append(ACTIVE[index].get())
                    .append(", queued=").append(queuedTaskEstimate(lane, pool))
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
        COMPILE_GOVERNOR_RUNNING.set(0);
    }

    public static void shutdownForTests() {
        ForkJoinPool oldNoisePool;
        ForkJoinPool oldCompilePool;
        ForkJoinPool oldWorkspacePool;
        ForkJoinPool oldTransactionalPool;
        ForkJoinPool oldSerialPool;
        ForkJoinPool oldCommitPool;

        synchronized (INIT_LOCK) {
            oldNoisePool = noisePool;
            oldCompilePool = compilePool;
            oldWorkspacePool = workspacePool;
            oldTransactionalPool = transactionalPool;
            oldSerialPool = serialPool;
            oldCommitPool = commitPool;

            noisePool = null;
            compilePool = null;
            workspacePool = null;
            transactionalPool = null;
            serialPool = null;
            commitPool = null;
            configSnapshot = ConfigSnapshot.defaults();
            resetMetrics();
            resetAdmissionForTests();
            initialized = false;
        }

        shutdownPool(oldNoisePool);
        shutdownPool(oldCompilePool);
        shutdownPool(oldWorkspacePool);
        shutdownPool(oldTransactionalPool);
        shutdownPool(oldSerialPool);
        shutdownPool(oldCommitPool);
    }

    private static Map<String, Object> laneSnapshot(Lane lane) {
        ForkJoinPool pool = existingPool(lane);
        int index = lane.ordinal();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parallelism", pool == null ? workersFor(lane, configSnapshot) : pool.getParallelism());
        out.put("poolSize", pool == null ? 0 : pool.getPoolSize());
        out.put("runningThreadCount", pool == null ? 0 : pool.getRunningThreadCount());
        out.put("activeThreadCount", pool == null ? 0 : pool.getActiveThreadCount());
        out.put("activeTasks", ACTIVE[index].get());
        out.put("queuedTaskEstimate", queuedTaskEstimate(lane, pool));
        out.put("queuedAdmissionSlots", ADMITTED_QUEUED[index].get());
        out.put("queuedSubmissionCount", pool == null ? 0 : pool.getQueuedSubmissionCount());
        out.put("stealCount", pool == null ? 0L : pool.getStealCount());
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
        return out;
    }

    private static Map<String, Object> governorSnapshot() {
        ConfigSnapshot config = configSnapshot;
        long worldgenPressure = worldgenPressure();
        updateMax(MAX_WORLDGEN_PRESSURE, worldgenPressure);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuTarget", config.cpuTarget());
        out.put("worldgenWorkers", worldgenWorkers(config));
        out.put("worldgenPressureTarget", worldgenPressureTarget(config));
        out.put("worldgenPressure", worldgenPressure);
        out.put("maxWorldgenPressure", MAX_WORLDGEN_PRESSURE.get());
        out.put("compileActiveLimit", compileActiveLimit(config, worldgenPressure));
        out.put("compileGovernorRunning", COMPILE_GOVERNOR_RUNNING.get());
        out.put("compileThrottleEnabled", config.compileWorkers() > 1);
        return out;
    }

    private static <T> T runGoverned(Lane lane, Supplier<T> supplier) {
        if (lane == Lane.COMPILE) {
            return runCompileGoverned(supplier);
        }
        return runMeasured(lane, supplier);
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
                GOVERNOR_WAIT_NANOS.addAndGet(Lane.COMPILE.ordinal(), System.nanoTime() - waitStart);
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

    private static long queuedTaskEstimate(Lane lane, ForkJoinPool pool) {
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
        return Math.max(1, (int) Math.ceil(worldgenWorkers(config) * config.cpuTarget()));
    }

    private static int worldgenWorkers(ConfigSnapshot config) {
        return Math.max(1,
                config.noiseWorkers()
                        + config.workspaceWorkers()
                        + config.transactionalWorkers()
                        + config.serialWorkers()
                        + config.commitWorkers()
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
        if (queueSlotHeld != null && queueSlotHeld.compareAndSet(true, false)) {
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
        return Thread.currentThread().getName().startsWith("GA-" + lane.name() + "-");
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

    public enum Lane {
        NOISE,
        COMPILE,
        WORKSPACE,
        TRANSACTIONAL,
        SERIAL,
        COMMIT;

        String jsonName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        boolean canInlineWhenBacklogged() {
            return this == COMPILE;
        }

        static Lane[] worldgenPressureLanes() {
            return new Lane[]{NOISE, WORKSPACE, TRANSACTIONAL, SERIAL, COMMIT};
        }
    }

    private record ConfigSnapshot(
            int noiseWorkers,
            int compileWorkers,
            int workspaceWorkers,
            int transactionalWorkers,
            int serialWorkers,
            int commitWorkers,
            int maxQueuedTasks,
            double cpuTarget
    ) {
        static ConfigSnapshot defaults() {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            return from(new GAConfig(), processors, false);
        }

        static ConfigSnapshot from(GAConfig config, int processors, boolean isDev) {
            int defaultNoise = Math.max(1, processors - (isDev ? 0 : 1));
            int defaultCompile = Math.min(4, Math.max(1, processors / 2));
            int defaultWorkspace = Math.min(Math.max(1, processors / 2), defaultNoise);
            int defaultTransactional = defaultWorkspace;
            return new ConfigSnapshot(
                    positiveOrDefault(config.schedulerNoiseWorkers, defaultNoise),
                    positiveOrDefault(config.schedulerCompileWorkers, defaultCompile),
                    positiveOrDefault(config.schedulerWorkspaceWorkers, defaultWorkspace),
                    positiveOrDefault(config.schedulerTransactionalWorkers, defaultTransactional),
                    serialWorkers(config.schedulerSerialWorkers),
                    positiveOrDefault(config.schedulerCommitWorkers, 1),
                    Math.max(0, config.schedulerMaxQueuedTasks),
                    config.schedulerCpuTarget <= 0.0D ? 0.85D : Math.min(1.0D, config.schedulerCpuTarget)
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("noiseWorkers", noiseWorkers);
            out.put("compileWorkers", compileWorkers);
            out.put("workspaceWorkers", workspaceWorkers);
            out.put("transactionalWorkers", transactionalWorkers);
            out.put("serialWorkers", serialWorkers);
            out.put("commitWorkers", commitWorkers);
            out.put("maxQueuedTasks", maxQueuedTasks);
            out.put("cpuTarget", cpuTarget);
            return out;
        }

        private static int positiveOrDefault(int value, int fallback) {
            return value > 0 ? value : fallback;
        }

        private static int serialWorkers(int configuredWorkers) {
            // Keep unsafe serial lane deterministic even if config requests more.
            return 1;
        }
    }
}
