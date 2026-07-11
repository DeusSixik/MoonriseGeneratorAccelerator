package dev.sixik.generator_accelerator.common.worldgen.parallel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACustomChunkGraphSchedulerTest {
    @AfterEach
    void tearDown() {
        GACustomChunkGraphScheduler.resetMetrics();
    }

    @Test
    void snapshotReportsEnabledStateAndResettableCounters() {
        Map<String, Object> snapshot = GACustomChunkGraphScheduler.snapshot();

        assertEquals(true, snapshot.get("enabled"));
        assertTrue(snapshot.containsKey("eagerEmptyRadius"));
        assertTrue(snapshot.containsKey("c2meChunkSystemClassPresent"));
        assertTrue(snapshot.containsKey("moonriseChunkSystemClassPresent"));
        assertEquals(0L, snapshot.get("tasksSubmitted"));
        assertEquals(0L, snapshot.get("tasksCompleted"));
        assertEquals(0L, snapshot.get("tasksFailed"));
        assertEquals(0L, snapshot.get("emptyNodesSubmitted"));
        assertEquals(0L, snapshot.get("graphNodesSubmitted"));
        assertEquals(0L, snapshot.get("generationGraphs"));
        assertEquals(0L, snapshot.get("loadingGraphs"));
    }
}
