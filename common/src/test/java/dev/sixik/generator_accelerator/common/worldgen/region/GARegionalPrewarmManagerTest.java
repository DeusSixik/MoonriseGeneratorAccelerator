package dev.sixik.generator_accelerator.common.worldgen.region;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GARegionalPrewarmManagerTest {
    static {
        System.setProperty("ga.region.prewarm.enabled", "true");
    }

    @BeforeEach
    void setUp() {
        GAScheduler.shutdownForTests();
        GARegionalPrewarmManager.clearForTests();
    }

    @AfterEach
    void tearDown() {
        GARegionalPrewarmManager.clearForTests();
        GAScheduler.shutdownForTests();
    }

    @Test
    void duplicateRequestsShareSingleCompileTask() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        var first = GARegionalPrewarmManager.request("region", () -> {
            builds.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        var second = GARegionalPrewarmManager.request("region", builds::incrementAndGet);
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertEquals(1, builds.get());
        Map<String, Object> snapshot = GARegionalPrewarmManager.snapshot();
        assertEquals(2L, ((Number) snapshot.get("requested")).longValue());
        assertTrue(((Number) snapshot.get("inflightHit")).longValue() >= 1L);
    }

    @Test
    void ensureInlineRunsColdMissSynchronously() {
        AtomicInteger builds = new AtomicInteger();
        GARegionalPrewarmManager.ensureInline("cold", builds::incrementAndGet);

        assertEquals(1, builds.get());
        Map<String, Object> snapshot = GARegionalPrewarmManager.snapshot();
        assertEquals(1L, ((Number) snapshot.get("inlineMiss")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestTypesAreTrackedIndependently() throws Exception {
        GARegionalPrewarmManager.request(
                GARegionalPrewarmManager.RequestType.CLIMATE,
                "climate",
                () -> { }
        ).get(10, TimeUnit.SECONDS);
        GARegionalPrewarmManager.ensureInline(
                GARegionalPrewarmManager.RequestType.SURFACE,
                "surface",
                () -> { }
        );

        Map<String, Object> snapshot = GARegionalPrewarmManager.snapshot();
        Map<String, Object> byType = (Map<String, Object>) snapshot.get("byType");
        Map<String, Object> climate = (Map<String, Object>) byType.get("climate");
        Map<String, Object> surface = (Map<String, Object>) byType.get("surface");

        assertEquals(1L, ((Number) climate.get("requested")).longValue());
        assertEquals(1L, ((Number) surface.get("inlineMiss")).longValue());
    }
}
