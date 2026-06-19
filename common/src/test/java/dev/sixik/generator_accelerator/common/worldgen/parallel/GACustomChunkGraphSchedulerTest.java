package dev.sixik.generator_accelerator.common.worldgen.parallel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.world.level.chunk.status.ChunkStatus;

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
        assertEquals(0L, snapshot.get("tasksSubmitted"));
        assertEquals(0L, snapshot.get("tasksCompleted"));
        assertEquals(0L, snapshot.get("tasksFailed"));
        assertEquals(0L, snapshot.get("emptyNodesSubmitted"));
        assertEquals(0L, snapshot.get("graphNodesSubmitted"));
        assertEquals(0L, snapshot.get("generationGraphs"));
        assertEquals(0L, snapshot.get("loadingGraphs"));
    }

    @Test
    void priorityScoreUsesFixedWeightsAndTieBreaksByInsertionSequence() {
        int noise = GACustomChunkGraphScheduler.priorityScore(ChunkStatus.NOISE, 2, 3, 1);
        int surface = GACustomChunkGraphScheduler.priorityScore(ChunkStatus.SURFACE, 0, 1, 0);

        assertEquals(500 + 2 * 64 + 3 * 32 - 24, noise);
        assertEquals(220 + 32, surface);
        assertTrue(GACustomChunkGraphScheduler.compareReadyNodeOrder(noise, 10L, surface, 1L) < 0);
        assertTrue(GACustomChunkGraphScheduler.compareReadyNodeOrder(surface, 1L, surface, 2L) < 0);
        assertTrue(GACustomChunkGraphScheduler.compareReadyNodeOrder(surface, 2L, surface, 1L) > 0);
    }
}
