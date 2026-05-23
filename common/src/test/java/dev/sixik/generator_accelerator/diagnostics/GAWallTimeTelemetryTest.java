package dev.sixik.generator_accelerator.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAWallTimeTelemetryTest {
    @AfterEach
    void tearDown() {
        GAWallTimeTelemetry.ENABLED = Boolean.getBoolean("ga.wallTimeTelemetry");
        GAWallTimeTelemetry.reset();
    }

    @Test
    void recordsElapsedStageWhenEnabled() {
        GAWallTimeTelemetry.ENABLED = true;
        GAWallTimeTelemetry.reset();

        GAWallTimeTelemetry.addElapsed(GAWallTimeTelemetry.Stage.SURFACE, 1234L);

        Map<?, ?> surface = stage("surface");
        assertEquals(1L, surface.get("count"));
        assertEquals(1234L, surface.get("nanos"));
        assertEquals(1234L, surface.get("avgNanos"));
        assertEquals(1234L, surface.get("maxNanos"));
    }

    @Test
    void completesFutureTimerOnCompletion() {
        GAWallTimeTelemetry.ENABLED = true;
        GAWallTimeTelemetry.reset();

        long start = GAWallTimeTelemetry.start(GAWallTimeTelemetry.Stage.FEATURES);
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> wrapped =
                GAWallTimeTelemetry.endWhenComplete(GAWallTimeTelemetry.Stage.FEATURES, start, future);

        future.complete("ok");

        assertEquals("ok", wrapped.join());
        Map<?, ?> features = stage("features");
        assertEquals(1L, features.get("count"));
        assertTrue((Long) features.get("nanos") >= 0L);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> stage(String name) {
        Map<String, Object> snapshot = GAWallTimeTelemetry.snapshot();
        return (Map<?, ?>) ((Map<String, Object>) snapshot.get("stages")).get(name);
    }
}
