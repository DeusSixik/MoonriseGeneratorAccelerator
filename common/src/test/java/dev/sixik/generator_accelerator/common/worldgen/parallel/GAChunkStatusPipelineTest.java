package dev.sixik.generator_accelerator.common.worldgen.parallel;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAChunkStatusPipelineTest {
    private static final String[] CONFIG_PROPERTIES = {
            "ga.config.schedulerWorldgenWorkers",
            "ga.config.schedulerNoiseWorkers",
            "ga.config.schedulerWorkspaceWorkers",
            "ga.config.schedulerTransactionalWorkers",
            "ga.config.schedulerMaxQueuedTasks",
            "ga.config.schedulerCommitBacklogThrottleThreshold",
            "ga.config.schedulerMailboxBacklogThrottleThreshold",
            "ga.config.schedulerHeapPressureTarget"
    };

    @BeforeEach
    void setUp() throws Exception {
        clearConfigProperties();
        System.setProperty("ga.config.schedulerWorldgenWorkers", "2");
        System.setProperty("ga.config.schedulerNoiseWorkers", "1");
        System.setProperty("ga.config.schedulerWorkspaceWorkers", "2");
        System.setProperty("ga.config.schedulerTransactionalWorkers", "2");
        System.setProperty("ga.config.schedulerMaxQueuedTasks", "0");
        resetCachedConfig();
        GAScheduler.shutdownForTests();
        GAChunkStatusPipeline.resetMetrics();
    }

    @AfterEach
    void tearDown() throws Exception {
        GAScheduler.shutdownForTests();
        clearConfigProperties();
        resetCachedConfig();
        GAChunkStatusPipeline.resetMetrics();
    }

    @Test
    void synchronousStageRunsOnRequestedLaneAndTracksMetrics() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        GAChunkStatusPipeline.schedule(
                GAChunkStatusPipeline.Stage.SURFACE,
                GAScheduler.Lane.WORKSPACE,
                null,
                null,
                () -> {
                    threadName.set(Thread.currentThread().getName());
                    return null;
                }
        ).get(10, TimeUnit.SECONDS);

        assertTrue(threadName.get().startsWith("GA-WORLDGEN-"), threadName.get());
        Map<?, ?> surface = stageSnapshot("surface");
        assertEquals(1L, surface.get("submitted"));
        assertEquals(1L, surface.get("completed"));
        assertEquals(0L, surface.get("failed"));
    }

    @Test
    void futureStageDoesNotBlockLaneWhileInnerFutureIsPending() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CompletableFuture<ChunkAccess> releaseFirst = new CompletableFuture<>();
        AtomicInteger secondRan = new AtomicInteger();

        CompletableFuture<ChunkAccess> first = GAChunkStatusPipeline.scheduleFuture(
                GAChunkStatusPipeline.Stage.SURFACE,
                GAScheduler.Lane.WORKSPACE,
                null,
                null,
                () -> {
                    firstEntered.countDown();
                    return releaseFirst;
                }
        );
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS));

        CompletableFuture<ChunkAccess> second = GAChunkStatusPipeline.schedule(
                GAChunkStatusPipeline.Stage.SURFACE,
                GAScheduler.Lane.WORKSPACE,
                null,
                null,
                () -> {
                    secondRan.incrementAndGet();
                    return null;
                }
        );

        second.get(10, TimeUnit.SECONDS);
        assertEquals(1, secondRan.get());
        releaseFirst.complete(null);
        first.get(10, TimeUnit.SECONDS);
    }

    private static Map<?, ?> stageSnapshot(String stage) {
        Map<String, Object> snapshot = GAChunkStatusPipeline.snapshot();
        Map<?, ?> stages = (Map<?, ?>) snapshot.get("stages");
        return (Map<?, ?>) stages.get(stage);
    }

    private static void clearConfigProperties() {
        for (String property : CONFIG_PROPERTIES) {
            System.clearProperty(property);
        }
    }

    private static void resetCachedConfig() throws Exception {
        Field config = GAConfigManager.class.getDeclaredField("config");
        config.setAccessible(true);
        config.set(null, null);
        Field wrapper = GAConfigManager.class.getDeclaredField("configWrapper");
        wrapper.setAccessible(true);
        wrapper.set(null, null);
    }
}
