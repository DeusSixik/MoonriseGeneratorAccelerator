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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
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
    private static final AtomicInteger[] ACTIVE = new AtomicInteger[Lane.values().length];

    private static volatile boolean initialized;
    private static volatile ForkJoinPool noisePool;
    private static volatile ForkJoinPool compilePool;
    private static volatile ForkJoinPool workspacePool;
    private static volatile ForkJoinPool commitPool;
    private static volatile ConfigSnapshot configSnapshot = ConfigSnapshot.defaults();

    static {
        for (int i = 0; i < ACTIVE.length; i++) {
            ACTIVE[i] = new AtomicInteger();
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

            noisePool = newPool(Lane.NOISE, snapshot.noiseWorkers());
            compilePool = newPool(Lane.COMPILE, snapshot.compileWorkers());
            workspacePool = newPool(Lane.WORKSPACE, snapshot.workspaceWorkers());
            commitPool = newPool(Lane.COMMIT, snapshot.commitWorkers());
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

    public static ForkJoinPool commitPool() {
        return forkJoinPool(Lane.COMMIT);
    }

    public static ForkJoinPool forkJoinPool(Lane lane) {
        ensureInitialized();
        return switch (lane) {
            case NOISE -> noisePool;
            case COMPILE -> compilePool;
            case WORKSPACE -> workspacePool;
            case COMMIT -> commitPool;
        };
    }

    public static <T> CompletableFuture<T> supplyAsync(Lane lane, Supplier<T> supplier) {
        ensureInitialized();
        int index = lane.ordinal();
        SUBMITTED.incrementAndGet(index);
        ForkJoinPool pool = forkJoinPool(lane);
        long queued = queuedTaskEstimate(pool);
        updateMax(MAX_QUEUED, index, queued);

        int maxQueuedTasks = configSnapshot.maxQueuedTasks();
        if (maxQueuedTasks > 0 && queued >= maxQueuedTasks && lane.canInlineWhenBacklogged()) {
            INLINE_RUNS.incrementAndGet(index);
            try {
                return CompletableFuture.completedFuture(runMeasured(lane, supplier));
            } catch (Throwable throwable) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(throwable);
                return failed;
            }
        }

        try {
            return CompletableFuture.supplyAsync(() -> runMeasured(lane, supplier), pool);
        } catch (RejectedExecutionException rejected) {
            INLINE_RUNS.incrementAndGet(index);
            try {
                return CompletableFuture.completedFuture(runMeasured(lane, supplier));
            } catch (Throwable throwable) {
                rejected.addSuppressed(throwable);
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(rejected);
                return failed;
            }
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
        int index = lane.ordinal();
        SUBMITTED.incrementAndGet(index);
        if (isCurrentLaneWorker(lane)) {
            runMeasured(lane, () -> {
                runnable.run();
                return null;
            });
            return;
        }
        ForkJoinPool pool = forkJoinPool(lane);
        updateMax(MAX_QUEUED, index, queuedTaskEstimate(pool));
        pool.submit(() -> runMeasured(lane, () -> {
            runnable.run();
            return null;
        })).get();
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
            ForkJoinPool pool = forkJoinPool(lane);
            int index = lane.ordinal();
            builder.append(lane.jsonName())
                    .append("(workers=").append(pool.getParallelism())
                    .append(", active=").append(ACTIVE[index].get())
                    .append(", queued=").append(queuedTaskEstimate(pool))
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
        }
    }

    private static Map<String, Object> laneSnapshot(Lane lane) {
        ForkJoinPool pool = forkJoinPool(lane);
        int index = lane.ordinal();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parallelism", pool.getParallelism());
        out.put("poolSize", pool.getPoolSize());
        out.put("runningThreadCount", pool.getRunningThreadCount());
        out.put("activeThreadCount", pool.getActiveThreadCount());
        out.put("activeTasks", ACTIVE[index].get());
        out.put("queuedTaskEstimate", queuedTaskEstimate(pool));
        out.put("queuedSubmissionCount", pool.getQueuedSubmissionCount());
        out.put("stealCount", pool.getStealCount());
        out.put("submitted", SUBMITTED.get(index));
        out.put("completed", COMPLETED.get(index));
        out.put("failed", FAILED.get(index));
        out.put("inlineRuns", INLINE_RUNS.get(index));
        out.put("executionNanos", EXECUTION_NANOS.get(index));
        out.put("maxActiveTasks", MAX_ACTIVE.get(index));
        out.put("maxQueuedTaskEstimate", MAX_QUEUED.get(index));
        return out;
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

    private static long queuedTaskEstimate(ForkJoinPool pool) {
        return pool.getQueuedTaskCount() + pool.getQueuedSubmissionCount();
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

    public enum Lane {
        NOISE,
        COMPILE,
        WORKSPACE,
        COMMIT;

        String jsonName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        boolean canInlineWhenBacklogged() {
            return this == COMPILE;
        }
    }

    private record ConfigSnapshot(
            int noiseWorkers,
            int compileWorkers,
            int workspaceWorkers,
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
            return new ConfigSnapshot(
                    positiveOrDefault(config.schedulerNoiseWorkers, defaultNoise),
                    positiveOrDefault(config.schedulerCompileWorkers, defaultCompile),
                    positiveOrDefault(config.schedulerWorkspaceWorkers, defaultWorkspace),
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
            out.put("commitWorkers", commitWorkers);
            out.put("maxQueuedTasks", maxQueuedTasks);
            out.put("cpuTarget", cpuTarget);
            return out;
        }

        private static int positiveOrDefault(int value, int fallback) {
            return value > 0 ? value : fallback;
        }
    }
}
