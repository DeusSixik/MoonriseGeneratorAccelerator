package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GAOuterLifecycleMetricsTest {
    @AfterEach
    void reset() {
        GAOuterLifecycleMetrics.resetGlobal();
    }

    @Test
    void recordsSnapshotAndMapCounters() {
        GAOuterLifecycleMetrics.resetGlobal();
        GALightingHandoffMask mask = GALightingHandoffMask.fromDirtyColumns(List.of(
                new GAColumnPosition(0, 0),
                new GAColumnPosition(1, 0)
        ));
        GASerializationBatchPlan batch = GASerializationBatchPlan.plan(List.of(
                new GASerializationChunk(0, 0, 10, GASerializationUrgency.URGENT),
                new GASerializationChunk(1, 0, 10, GASerializationUrgency.NORMAL)
        ), 4, 100);

        GAOuterLifecycleMetrics.recordLightingHandoff(mask);
        GAOuterLifecycleMetrics.recordSerializationBatch(batch);
        GAOuterLifecycleMetrics.recordPublishingDecision(GAPublishingGuardDecision.evaluate(true, true, true, false));
        GAOuterLifecycleMetrics.recordPublishingDecision(GAPublishingGuardDecision.evaluate(false, true, true, false));
        GAOuterLifecycleMetrics.recordPublishingDecision(GAPublishingGuardDecision.evaluate(true, true, true, true));

        GAOuterLifecycleSnapshot snapshot = GAOuterLifecycleMetrics.snapshot();
        assertEquals(new GAOuterLifecycleSnapshot(1, 2, 1, 2, 1, 1, 1), snapshot);

        Map<String, Object> map = GAOuterLifecycleMetrics.snapshotMap();
        assertEquals(1L, map.get("lightingHandoffs"));
        assertEquals(2L, map.get("dirtyLightColumns"));
        assertEquals(1L, map.get("serializationBatches"));
        assertEquals(2L, map.get("serializedChunks"));
        assertEquals(1L, map.get("promotionAllows"));
        assertEquals(1L, map.get("promotionDefers"));
        assertEquals(1L, map.get("promotionFallbacks"));
    }
}
