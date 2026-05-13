package dev.sixik.generator_accelerator.common.features.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecorationConflictSchedulerMetricsTest {
    @AfterEach
    void reset() {
        DecorationConflictSchedulerMetrics.reset();
    }

    @Test
    void snapshotTracksBatchCommitAndFallbackCounters() {
        DecorationConflictSchedulerMetrics.reset();

        DecorationConflictSchedulerMetrics.recordSubmitted(3);
        DecorationConflictSchedulerMetrics.recordCommitted(2, 17L);
        DecorationConflictSchedulerMetrics.recordConflictFallback();
        DecorationConflictSchedulerMetrics.recordFailureFallback();
        DecorationConflictSchedulerMetrics.recordSmallBatchFallback();

        Map<String, Object> snapshot = DecorationConflictSchedulerMetrics.snapshot();
        assertEquals(1L, snapshot.get("batchesSubmitted"));
        assertEquals(1L, snapshot.get("batchesCommitted"));
        assertEquals(3L, snapshot.get("featuresSubmitted"));
        assertEquals(2L, snapshot.get("featuresCommitted"));
        assertEquals(17L, snapshot.get("journalWritesCommitted"));
        assertEquals(1L, snapshot.get("conflictFallbacks"));
        assertEquals(1L, snapshot.get("failureFallbacks"));
        assertEquals(1L, snapshot.get("smallBatchFallbacks"));
    }
}
