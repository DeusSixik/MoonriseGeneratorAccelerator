package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GASerializationBatchPlanTest {
    @Test
    void ordersByUrgencyThenChunkAndCutsByBudget() {
        GASerializationChunk background = new GASerializationChunk(0, 0, 10, GASerializationUrgency.BACKGROUND);
        GASerializationChunk urgentFar = new GASerializationChunk(4, 0, 20, GASerializationUrgency.URGENT);
        GASerializationChunk urgentNear = new GASerializationChunk(1, 0, 20, GASerializationUrgency.URGENT);
        GASerializationChunk normal = new GASerializationChunk(2, 0, 20, GASerializationUrgency.NORMAL);

        GASerializationBatchPlan plan = GASerializationBatchPlan.plan(
                List.of(background, urgentFar, normal, urgentNear),
                3,
                50
        );

        assertEquals(List.of(urgentNear, urgentFar), plan.chunks());
        assertEquals(40, plan.estimatedBytes());
        assertEquals(2, plan.chunkCount());
        assertTrue(plan.truncated());
    }

    @Test
    void includesOversizedFirstChunkToGuaranteeProgress() {
        GASerializationChunk huge = new GASerializationChunk(0, 0, 100, GASerializationUrgency.NORMAL);
        GASerializationBatchPlan plan = GASerializationBatchPlan.plan(List.of(huge), 4, 10);

        assertEquals(List.of(huge), plan.chunks());
        assertEquals(100, plan.estimatedBytes());
    }

    @Test
    void copiesSelectedChunks() {
        List<GASerializationChunk> input = new ArrayList<>();
        GASerializationChunk chunk = new GASerializationChunk(1, 2, -5, null);
        input.add(chunk);

        GASerializationBatchPlan plan = GASerializationBatchPlan.plan(input, 2, 10);
        input.clear();

        assertEquals(List.of(chunk), plan.chunks());
        assertEquals(0, chunk.estimatedBytes());
        assertEquals(GASerializationUrgency.NORMAL, chunk.urgency());
        assertThrows(UnsupportedOperationException.class, () -> plan.chunks().add(chunk));
    }
}
