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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GASchedulerTest {
    private static final String[] TEST_CONFIG_PROPERTIES = {
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
            "ga.config.schedulerV2Enabled",
            "ga.config.schedulerHeapPressureTarget"
    };

    @BeforeEach
    void setUp() throws Exception {
        GAScheduler.shutdownForTests();
        clearTestConfigProperties();
        System.setProperty("ga.config.schedulerV2Enabled", "false");
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

        assertTrue(result.startsWith("GA-NOISE-"), result);
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

        assertTrue(transactionalThread.startsWith("GA-TRANSACTIONAL-"), transactionalThread);
        assertTrue(serialThread.startsWith("GA-SERIAL-"), serialThread);

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
        assertTrue(firstThread.startsWith("GA-NOISE-"), firstThread);
        assertEquals(1L, noiseMetric("submitted"));

        GAScheduler.shutdownForTests();
        assertTrue(firstPool.isShutdown() || firstPool.isTerminated(), firstPool.toString());

        String secondThread = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () ->
                Thread.currentThread().getName()
        ).get(10, TimeUnit.SECONDS);
        ForkJoinPool secondPool = GAScheduler.noisePool();

        assertNotSame(firstPool, secondPool);
        assertTrue(secondThread.startsWith("GA-NOISE-"), secondThread);
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
        assertTrue(childThread.startsWith("GA-TRANSACTIONAL-"), childThread);
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
    void worldgenGovernorSerializesDifferentLanesAtLimitOne() throws Exception {
        configureGovernorPressure();
        CountDownLatch noiseStarted = new CountDownLatch(1);
        CountDownLatch releaseNoise = new CountDownLatch(1);
        CountDownLatch workspaceStarted = new CountDownLatch(1);
        CompletableFuture<String> noise = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            noiseStarted.countDown();
            awaitLatch(releaseNoise);
            return "noise";
        });
        CompletableFuture<String> workspace = null;
        try {
            assertTrue(noiseStarted.await(10, TimeUnit.SECONDS));
            workspace = GAScheduler.supplyAsync(GAScheduler.Lane.WORKSPACE, () -> {
                workspaceStarted.countDown();
                return "workspace";
            });

            Thread.sleep(200L);
            assertEquals(1L, workspaceStarted.getCount(), "workspace lane should wait for the global worldgen slot");
            assertEquals(1, governorSnapshot().get("worldgenGovernorRunning"));
            assertTrue(waitForLaneMetricAtLeast(GAScheduler.Lane.WORKSPACE, "governorThrottled", 1L));

            releaseNoise.countDown();
            assertEquals("noise", noise.get(10, TimeUnit.SECONDS));
            assertTrue(workspaceStarted.await(10, TimeUnit.SECONDS));
            assertEquals("workspace", workspace.get(10, TimeUnit.SECONDS));
        } finally {
            releaseNoise.countDown();
            noise.cancel(true);
            if (workspace != null) {
                workspace.cancel(true);
            }
        }
    }

    @Test
    void resetMetricsDoesNotClearLiveWorldgenGovernorState() throws Exception {
        configureGovernorPressure();
        CountDownLatch noiseStarted = new CountDownLatch(1);
        CountDownLatch releaseNoise = new CountDownLatch(1);
        CompletableFuture<String> noise = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () -> {
            noiseStarted.countDown();
            awaitLatch(releaseNoise);
            return "noise";
        });
        try {
            assertTrue(noiseStarted.await(10, TimeUnit.SECONDS));
            assertEquals(1, governorSnapshot().get("worldgenGovernorRunning"));

            GAScheduler.resetMetrics();

            assertEquals(1, governorSnapshot().get("worldgenGovernorRunning"));
            releaseNoise.countDown();
            assertEquals("noise", noise.get(10, TimeUnit.SECONDS));
        } finally {
            releaseNoise.countDown();
            noise.cancel(true);
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
        System.setProperty(workerProperty, "1");
        System.setProperty("ga.config.schedulerMaxQueuedTasks", "1");
        resetCachedConfig();
        GAScheduler.shutdownForTests();
    }

    private static void configureGovernorPressure() throws Exception {
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
