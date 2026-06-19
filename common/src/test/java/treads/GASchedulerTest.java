package treads;

import dev.sixik.generator_accelerator.common.treads.GAFastLocalHolder;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GASchedulerTest {
    private static final String[] TEST_CONFIG_PROPERTIES = {
            "ga.config.schedulerWorldgenWorkers",
            "ga.config.schedulerNoiseWorkers",
            "ga.config.schedulerCompileWorkers",
            "ga.config.schedulerWorkspaceWorkers",
            "ga.config.schedulerTransactionalWorkers",
            "ga.config.schedulerSerialWorkers",
            "ga.config.schedulerCommitWorkers",
            "ga.config.schedulerCpuTarget",
            "ga.config.schedulerMaxQueuedTasks",
            "ga.config.schedulerCommitBacklogThrottleThreshold",
            "ga.config.schedulerMailboxBacklogThrottleThreshold",
            "ga.config.schedulerHeapPressureTarget"
    };

    @BeforeEach
    void setUp() throws Exception {
        GAScheduler.shutdownForTests();
        clearTestConfigProperties();
        resetCachedConfig();
    }

    @AfterEach
    void tearDown() throws Exception {
        GAScheduler.shutdownForTests();
        clearTestConfigProperties();
        resetCachedConfig();
    }

    @Test
    void noiseLaneUsesGaFastLocalWorker() throws Exception {
        String result = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () ->
                Thread.currentThread().getName() + ":" + (Thread.currentThread() instanceof GAFastLocalHolder)
        ).get(10, TimeUnit.SECONDS);

        assertTrue(result.startsWith("GA-WORLDGEN-"), result);
        assertTrue(result.endsWith(":true"), result);

        Map<String, Object> snapshot = GAScheduler.snapshot();
        assertTrue(snapshot.containsKey("lanes"));
    }

    @Test
    void phase2LaneSkeletonUsesNamedWorkersAndDiagnostics() throws Exception {
        String transactionalThread = GAScheduler.supplyAsync(GAScheduler.Lane.TRANSACTIONAL, () ->
                Thread.currentThread().getName()
        ).get(10, TimeUnit.SECONDS);
        String serialThread = GAScheduler.supplyAsync(GAScheduler.Lane.SERIAL, () ->
                Thread.currentThread().getName()
        ).get(10, TimeUnit.SECONDS);

        assertTrue(transactionalThread.startsWith("GA-WORLDGEN-"), transactionalThread);
        assertTrue(serialThread.startsWith("GA-WORLDGEN-"), serialThread);

        Map<String, Object> snapshot = GAScheduler.snapshot();
        assertTrue(snapshot.get("lanes") instanceof Map<?, ?>);
        Map<?, ?> lanes = (Map<?, ?>) snapshot.get("lanes");
        assertTrue(lanes.containsKey("transactional"), lanes.toString());
        assertTrue(lanes.containsKey("serial"), lanes.toString());

        assertTrue(snapshot.get("config") instanceof Map<?, ?>);
        Map<?, ?> config = (Map<?, ?>) snapshot.get("config");
        assertTrue(config.containsKey("transactionalWorkers"), config.toString());
        assertTrue(config.containsKey("serialWorkers"), config.toString());
        assertTrue(config.get("serialWorkers").equals(1), config.toString());
        assertTrue(GAScheduler.serialPool().getParallelism() == 1);
    }

    @Test
    void governorSnapshotReportsCpuTargetPressureAndSerialClamp() throws Exception {
        configureGovernorPressure();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> blocker = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            started.countDown();
            awaitLatch(release);
            return "blocker";
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));

            Map<String, Object> governor = governorSnapshot();
            assertEquals(0.1D, (Double) governor.get("cpuTarget"), 0.0001D);
            assertEquals(1, governor.get("worldgenPressureTarget"));
            assertTrue(((Number) governor.get("worldgenPressure")).longValue() >= 1L, governor.toString());
            assertEquals(1, governor.get("compileActiveLimit"));

            Map<?, ?> config = (Map<?, ?>) GAScheduler.snapshot().get("config");
            assertEquals(1, config.get("serialWorkers"));
            assertEquals(1, GAScheduler.serialPool().getParallelism());
            release.countDown();
            assertEquals("blocker", blocker.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownForTestsRebuildsPoolsAndResetsMetrics() throws Exception {
        String firstThread = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () ->
                Thread.currentThread().getName()
        ).get(10, TimeUnit.SECONDS);
        ForkJoinPool firstPool = GAScheduler.noisePool();
        assertTrue(firstThread.startsWith("GA-WORLDGEN-"), firstThread);
        assertEquals(1L, noiseMetric("submitted"));

        GAScheduler.shutdownForTests();
        assertTrue(firstPool.isShutdown() || firstPool.isTerminated(), firstPool.toString());

        String secondThread = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () ->
                Thread.currentThread().getName()
        ).get(10, TimeUnit.SECONDS);
        ForkJoinPool secondPool = GAScheduler.noisePool();

        assertNotSame(firstPool, secondPool);
        assertTrue(secondThread.startsWith("GA-WORLDGEN-"), secondThread);
        assertEquals(1L, noiseMetric("submitted"));
        assertEquals(1L, noiseMetric("completed"));
    }

    @Test
    void nonInlineLaneRejectsWhenQueueLimitReached() throws Exception {
        configureBoundedLane("ga.config.schedulerNoiseWorkers");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> blocker = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            started.countDown();
            awaitLatch(release);
            return "blocker";
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<String> queued = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> "queued");
            assertTrue(waitForQueued(GAScheduler.Lane.NOISE, 1L));

            CompletableFuture<String> rejected = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> "rejected");

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rejected.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RejectedExecutionException, failure.toString());
            assertEquals(1L, laneMetric(GAScheduler.Lane.NOISE, "failed"));
            release.countDown();
            assertEquals("blocker", blocker.get(10, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void invokeBlockingUsesQueueLimitForNonInlineLanes() throws Exception {
        configureBoundedLane("ga.config.schedulerNoiseWorkers");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> blocker = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            started.countDown();
            awaitLatch(release);
            return "blocker";
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<String> queued = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> "queued");
            assertTrue(waitForQueued(GAScheduler.Lane.NOISE, 1L));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> GAScheduler.invokeBlocking(GAScheduler.Lane.NOISE, () -> {
                    }));

            assertTrue(failure.getCause() instanceof RejectedExecutionException, failure.toString());
            release.countDown();
            assertEquals("blocker", blocker.get(10, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancelledQueuedTaskReleasesAdmissionSlot() throws Exception {
        configureBoundedLane("ga.config.schedulerNoiseWorkers");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> blocker = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            started.countDown();
            awaitLatch(release);
            return "blocker";
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<String> cancelled = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> "cancelled");
            assertTrue(waitForLaneMetric(GAScheduler.Lane.NOISE, "queuedAdmissionSlots", 1L));

            assertTrue(cancelled.cancel(false));
            assertTrue(waitForLaneMetric(GAScheduler.Lane.NOISE, "queuedAdmissionSlots", 0L));

            CompletableFuture<String> afterCancel = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> "after-cancel");
            assertTrue(waitForLaneMetric(GAScheduler.Lane.NOISE, "queuedAdmissionSlots", 1L));
            release.countDown();
            assertEquals("blocker", blocker.get(10, TimeUnit.SECONDS));
            assertEquals("after-cancel", afterCancel.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void compileLaneInlinesWhenQueueLimitReached() throws Exception {
        configureBoundedLane("ga.config.schedulerCompileWorkers");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> blocker = GAScheduler.supplyAsync(GAScheduler.Lane.COMPILE, () -> {
            started.countDown();
            awaitLatch(release);
            return "blocker";
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<String> queued = GAScheduler.supplyAsync(GAScheduler.Lane.COMPILE, () -> "queued");
            assertTrue(waitForQueued(GAScheduler.Lane.COMPILE, 1L));

            String callerThread = Thread.currentThread().getName();
            CompletableFuture<String> inline = GAScheduler.supplyAsync(GAScheduler.Lane.COMPILE,
                    () -> Thread.currentThread().getName());

            assertEquals(callerThread, inline.get(10, TimeUnit.SECONDS));
            assertEquals(1L, laneMetric(GAScheduler.Lane.COMPILE, "inlineRuns"));
            release.countDown();
            assertEquals("blocker", blocker.get(10, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void nestedSameLaneWorkRunsInlineToAvoidWorkerStarvation() throws Exception {
        System.setProperty("ga.config.schedulerTransactionalWorkers", "1");
        System.setProperty("ga.config.schedulerMaxQueuedTasks", "0");
        resetCachedConfig();
        GAScheduler.shutdownForTests();

        CompletableFuture<String> parent = GAScheduler.supplyAsync(GAScheduler.Lane.TRANSACTIONAL, () ->
                GAScheduler.supplyNestedAsync(GAScheduler.Lane.TRANSACTIONAL, () ->
                        Thread.currentThread().getName()
                ).join()
        );

        String childThread = parent.get(10, TimeUnit.SECONDS);
        assertTrue(childThread.startsWith("GA-WORLDGEN-"), childThread);
        assertEquals(1L, laneMetric(GAScheduler.Lane.TRANSACTIONAL, "inlineRuns"));
    }

    @Test
    void invokeBlockingFromWorldgenWorkerBypassesGovernorToAvoidSelfDeadlock() throws Exception {
        System.setProperty("ga.config.schedulerNoiseWorkers", "1");
        System.setProperty("ga.config.schedulerWorkspaceWorkers", "1");
        System.setProperty("ga.config.schedulerTransactionalWorkers", "1");
        System.setProperty("ga.config.schedulerCpuTarget", "0.1");
        System.setProperty("ga.config.schedulerMaxQueuedTasks", "0");
        resetCachedConfig();
        GAScheduler.shutdownForTests();

        CompletableFuture<String> parent = GAScheduler.supplyAsync(GAScheduler.Lane.TRANSACTIONAL, () -> {
            try {
                GAScheduler.invokeBlocking(GAScheduler.Lane.WORKSPACE, () -> {
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            } catch (ExecutionException failure) {
                throw new RuntimeException(failure);
            }
            return "done";
        });

        assertEquals("done", parent.get(10, TimeUnit.SECONDS));
        assertEquals(1L, laneMetric(GAScheduler.Lane.WORKSPACE, "admissionAccepted"));
        assertEquals(1L, laneMetric(GAScheduler.Lane.WORKSPACE, "inlineRuns"));
    }

    @Test
    void compileLaneThrottlesParallelWarmupDuringWorldgenPressure() throws Exception {
        configureGovernorPressure();
        CountDownLatch noiseStarted = new CountDownLatch(1);
        CountDownLatch releaseNoise = new CountDownLatch(1);
        CompletableFuture<String> noise = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            noiseStarted.countDown();
            awaitLatch(releaseNoise);
            return "noise";
        });

        CountDownLatch firstCompileStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCompile = new CountDownLatch(1);
        CountDownLatch secondCompileStarted = new CountDownLatch(1);
        CompletableFuture<String> firstCompile = null;
        CompletableFuture<String> secondCompile = null;
        try {
            assertTrue(noiseStarted.await(10, TimeUnit.SECONDS));
            firstCompile = GAScheduler.supplyAsync(GAScheduler.Lane.COMPILE, () -> {
                firstCompileStarted.countDown();
                awaitLatch(releaseFirstCompile);
                return "first";
            });
            assertTrue(firstCompileStarted.await(10, TimeUnit.SECONDS));

            secondCompile = GAScheduler.supplyAsync(GAScheduler.Lane.COMPILE, () -> {
                secondCompileStarted.countDown();
                return "second";
            });

            Thread.sleep(200L);
            assertEquals(1L, secondCompileStarted.getCount(), "second compile should wait behind governor");
            assertTrue(waitForLaneMetricAtLeast(GAScheduler.Lane.COMPILE, "governorThrottled", 1L));

            releaseFirstCompile.countDown();
            assertEquals("first", firstCompile.get(10, TimeUnit.SECONDS));
            assertTrue(secondCompileStarted.await(10, TimeUnit.SECONDS));
            assertEquals("second", secondCompile.get(10, TimeUnit.SECONDS));
            releaseNoise.countDown();
            assertEquals("noise", noise.get(10, TimeUnit.SECONDS));
            assertTrue(((Number) laneMetric(GAScheduler.Lane.COMPILE, "governorWaitNanos")).longValue() > 0L);
        } finally {
            releaseFirstCompile.countDown();
            releaseNoise.countDown();
            if (firstCompile != null) {
                firstCompile.cancel(true);
            }
            if (secondCompile != null) {
                secondCompile.cancel(true);
            }
        }
    }

    @Test
    void admissionMetricsTrackAcceptsAndQueueRejections() throws Exception {
        configureBoundedLane("ga.config.schedulerNoiseWorkers");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> blocker = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            started.countDown();
            awaitLatch(release);
            return "blocker";
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<String> queued = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> "queued");
            assertTrue(waitForQueued(GAScheduler.Lane.NOISE, 1L));
            CompletableFuture<String> rejected = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> "rejected");

            assertThrows(ExecutionException.class, () -> rejected.get(10, TimeUnit.SECONDS));
            assertEquals(2L, laneMetric(GAScheduler.Lane.NOISE, "admissionAccepted"));
            assertEquals(1L, laneMetric(GAScheduler.Lane.NOISE, "admissionRejected"));
            release.countDown();
            assertEquals("blocker", blocker.get(10, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void overlappingConflictRegionsNeverRunConcurrently() throws Exception {
        configureAdaptiveWorkers(2, 2, 1, 1, 1.0D);
        GAScheduler.ConflictRegion firstRegion = GAScheduler.conflictRegion(0, 0, 1);
        GAScheduler.ConflictRegion secondRegion = GAScheduler.conflictRegion(0, 0, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        CompletableFuture<String> first = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, 0, firstRegion, () -> {
            int active = concurrent.incrementAndGet();
            maxConcurrent.updateAndGet(current -> Math.max(current, active));
            firstStarted.countDown();
            awaitLatch(releaseFirst);
            concurrent.decrementAndGet();
            return "first";
        });
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS));

        CompletableFuture<String> second = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, 0, secondRegion, () -> {
            int active = concurrent.incrementAndGet();
            maxConcurrent.updateAndGet(current -> Math.max(current, active));
            concurrent.decrementAndGet();
            return "second";
        });

        Thread.sleep(200L);
        assertEquals(1, maxConcurrent.get());
        assertTrue(((Number) laneMetric(GAScheduler.Lane.NOISE, "spatialDeferred")).longValue() > 0L);
        releaseFirst.countDown();
        assertEquals("first", first.get(10, TimeUnit.SECONDS));
        assertEquals("second", second.get(10, TimeUnit.SECONDS));
        assertEquals(1, maxConcurrent.get());
    }

    @Test
    void nonOverlappingConflictRegionsCanRunConcurrently() throws Exception {
        configureAdaptiveWorkers(2, 2, 1, 1, 1.0D);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        CompletableFuture<String> first = GAScheduler.supplyAsync(
                GAScheduler.Lane.NOISE,
                0,
                GAScheduler.conflictRegion(0, 0, 1),
                () -> concurrentTask("first", bothStarted, release, concurrent, maxConcurrent)
        );
        CompletableFuture<String> second = GAScheduler.supplyAsync(
                GAScheduler.Lane.NOISE,
                0,
                GAScheduler.conflictRegion(4096, 4096, 1),
                () -> concurrentTask("second", bothStarted, release, concurrent, maxConcurrent)
        );

        assertTrue(bothStarted.await(10, TimeUnit.SECONDS));
        release.countDown();
        assertEquals("first", first.get(10, TimeUnit.SECONDS));
        assertEquals("second", second.get(10, TimeUnit.SECONDS));
        assertEquals(2, maxConcurrent.get());
    }

    @Test
    void logicalLaneCreditsAllowDifferentQueuedLanesToMakeProgress() throws Exception {
        configureAdaptiveWorkers(2, 1, 1, 1, 1.0D);
        CountDownLatch noiseStarted = new CountDownLatch(1);
        CountDownLatch workspaceStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> noise = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            noiseStarted.countDown();
            awaitLatch(release);
            return "noise";
        });
        CompletableFuture<String> workspace = GAScheduler.supplyAsync(GAScheduler.Lane.WORKSPACE, () -> {
            workspaceStarted.countDown();
            awaitLatch(release);
            return "workspace";
        });

        assertTrue(noiseStarted.await(10, TimeUnit.SECONDS));
        assertTrue(workspaceStarted.await(10, TimeUnit.SECONDS));
        release.countDown();
        assertEquals("noise", noise.get(10, TimeUnit.SECONDS));
        assertEquals("workspace", workspace.get(10, TimeUnit.SECONDS));
        assertEquals(1L, laneMetric(GAScheduler.Lane.NOISE, "completed"));
        assertEquals(1L, laneMetric(GAScheduler.Lane.WORKSPACE, "completed"));
    }

    @Test
    void ewmaTaskCostUpdatesAfterCompletion() throws Exception {
        configureAdaptiveWorkers(1, 1, 1, 1, 1.0D);
        long before = ((Number) laneMetric(GAScheduler.Lane.WORKSPACE, "ewmaNanos")).longValue();

        assertEquals("done", GAScheduler.supplyAsync(GAScheduler.Lane.WORKSPACE, () -> "done")
                .get(10, TimeUnit.SECONDS));

        long ewma = ((Number) laneMetric(GAScheduler.Lane.WORKSPACE, "ewmaNanos")).longValue();
        assertTrue(ewma >= 0L, Long.toString(ewma));
        assertTrue(ewma != before, Long.toString(ewma));
    }

    @Test
    void commitBacklogReducesAdaptiveActiveTarget() throws Exception {
        configureAdaptiveWorkers(4, 2, 2, 1, 1.0D);
        System.setProperty("ga.config.schedulerCommitBacklogThrottleThreshold", "1");
        resetCachedConfig();
        GAScheduler.shutdownForTests();

        CountDownLatch commitStarted = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CompletableFuture<String> commit = GAScheduler.supplyAsync(GAScheduler.Lane.COMMIT, () -> {
            commitStarted.countDown();
            awaitLatch(releaseCommit);
            return "commit";
        });
        try {
            assertTrue(commitStarted.await(10, TimeUnit.SECONDS));
            Map<?, ?> adaptive = (Map<?, ?>) GAScheduler.snapshot().get("adaptive");
            assertEquals(1, adaptive.get("activeTarget"));
            releaseCommit.countDown();
            assertEquals("commit", commit.get(10, TimeUnit.SECONDS));
        } finally {
            releaseCommit.countDown();
        }
    }

    private static Object noiseMetric(String metric) {
        return laneMetric(GAScheduler.Lane.NOISE, metric);
    }

    private static Object laneMetric(GAScheduler.Lane lane, String metric) {
        Map<String, Object> snapshot = GAScheduler.snapshot();
        Map<?, ?> lanes = (Map<?, ?>) snapshot.get("lanes");
        Map<?, ?> laneSnapshot = (Map<?, ?>) lanes.get(lane.name().toLowerCase(java.util.Locale.ROOT));
        return laneSnapshot.get(metric);
    }

    private static void configureBoundedLane(String workerProperty) throws Exception {
        System.setProperty("ga.config.schedulerWorldgenWorkers", "1");
        System.setProperty(workerProperty, "1");
        System.setProperty("ga.config.schedulerMaxQueuedTasks", "1");
        resetCachedConfig();
        GAScheduler.shutdownForTests();
    }

    private static void configureAdaptiveWorkers(
            int worldgenWorkers,
            int noiseWorkers,
            int workspaceWorkers,
            int transactionalWorkers,
            double cpuTarget
    ) throws Exception {
        System.setProperty("ga.config.schedulerWorldgenWorkers", Integer.toString(worldgenWorkers));
        System.setProperty("ga.config.schedulerNoiseWorkers", Integer.toString(noiseWorkers));
        System.setProperty("ga.config.schedulerWorkspaceWorkers", Integer.toString(workspaceWorkers));
        System.setProperty("ga.config.schedulerTransactionalWorkers", Integer.toString(transactionalWorkers));
        System.setProperty("ga.config.schedulerCpuTarget", Double.toString(cpuTarget));
        System.setProperty("ga.config.schedulerMaxQueuedTasks", "0");
        resetCachedConfig();
        GAScheduler.shutdownForTests();
    }

    private static String concurrentTask(
            String result,
            CountDownLatch started,
            CountDownLatch release,
            AtomicInteger concurrent,
            AtomicInteger maxConcurrent
    ) {
        int active = concurrent.incrementAndGet();
        maxConcurrent.updateAndGet(current -> Math.max(current, active));
        started.countDown();
        awaitLatch(release);
        concurrent.decrementAndGet();
        return result;
    }

    private static void configureGovernorPressure() throws Exception {
        System.setProperty("ga.config.schedulerWorldgenWorkers", "1");
        System.setProperty("ga.config.schedulerNoiseWorkers", "1");
        System.setProperty("ga.config.schedulerCompileWorkers", "2");
        System.setProperty("ga.config.schedulerWorkspaceWorkers", "1");
        System.setProperty("ga.config.schedulerTransactionalWorkers", "1");
        System.setProperty("ga.config.schedulerSerialWorkers", "4");
        System.setProperty("ga.config.schedulerCommitWorkers", "1");
        System.setProperty("ga.config.schedulerCpuTarget", "0.1");
        System.setProperty("ga.config.schedulerMaxQueuedTasks", "0");
        resetCachedConfig();
        GAScheduler.shutdownForTests();
    }

    private static Map<String, Object> governorSnapshot() {
        Map<String, Object> snapshot = GAScheduler.snapshot();
        return (Map<String, Object>) snapshot.get("governor");
    }

    private static boolean waitForQueued(GAScheduler.Lane lane, long minQueued) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < deadline) {
            Object queued = laneMetric(lane, "queuedTaskEstimate");
            if (queued instanceof Number number && number.longValue() >= minQueued) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static boolean waitForLaneMetric(GAScheduler.Lane lane, String metric, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < deadline) {
            Object value = laneMetric(lane, metric);
            if (value instanceof Number number && number.longValue() == expected) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static boolean waitForLaneMetricAtLeast(GAScheduler.Lane lane, String metric, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < deadline) {
            Object value = laneMetric(lane, metric);
            if (value instanceof Number number && number.longValue() >= expected) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void clearTestConfigProperties() {
        for (String property : TEST_CONFIG_PROPERTIES) {
            System.clearProperty(property);
        }
    }

    private static void resetCachedConfig() throws Exception {
        setStaticField("config", null);
        setStaticField("configWrapper", null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = GAConfigManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
