package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACommitEngineTest {
    @Test
    void metricsSnapshotCountsResolvedReplayAndFailures() {
        GACommitMetrics.resetGlobal();
        GACommitCommand<String> early = command(0, 0, "early");
        GACommitCommand<String> late = command(0, 1, "late");
        GACommitCommand<String> fail = command(1, 2, "fail");

        GACommitEngine.GACommitExecution<String> execution = GACommitEngine.execute(
                GACommitBatch.of(List.of(late, fail, early)),
                GACommitCollisionPolicy.FIRST_WRITE_WINS,
                command -> {
                    if ("fail".equals(command.value())) {
                        throw new IllegalStateException("boom");
                    }
                }
        );

        assertEquals(List.of(early, fail), execution.resolved().accepted());
        assertEquals(List.of(late), execution.resolved().rejected());
        assertEquals(1, execution.failures().size());
        assertEquals(fail, execution.failures().get(0).command());
        assertEquals(new GACommitMetrics(1, 3, 2, 1, 1, execution.metrics().executionNanos(), 1), execution.metrics());
        assertTrue(execution.metrics().executionNanos() >= 0L);

        Map<String, Object> global = GACommitMetrics.snapshotGlobal();
        assertEquals(1L, global.get("batches"));
        assertEquals(3L, global.get("input"));
        assertEquals(2L, global.get("accepted"));
        assertEquals(1L, global.get("rejected"));
        assertEquals(1L, global.get("collisions"));
        assertEquals(1L, global.get("failures"));
        assertEquals(new GACommitMetrics(1, 3, 2, 1, 1, execution.metrics().executionNanos(), 1),
                GACommitMetrics.snapshotGlobalMetrics());

        GACommitMetrics.resetGlobal();
        assertEquals(GACommitMetrics.empty(), GACommitMetrics.snapshotGlobalMetrics());
    }

    @Test
    void replayOrderIsDeterministicIndependentOfInputOrder() {
        GACommitCommand<String> a = command(0, 0, "a");
        GACommitCommand<String> b = command(1, 1, "b");
        GACommitCommand<String> c = command(2, 2, "c");
        List<GACommitCommand<String>> shuffled = new ArrayList<>(List.of(c, a, b));
        List<String> replayA = replayValues(shuffled);

        Collections.reverse(shuffled);
        List<String> replayB = replayValues(shuffled);

        assertEquals(List.of("a", "b", "c"), replayA);
        assertEquals(replayA, replayB);
    }

    @Test
    void sectionReplayGroupsAndAppliesInStableSectionOrder() {
        GACommitMetrics.resetGlobal();
        GACommitCommand<String> high = new GACommitCommand<>(
                new GABlockPosition(0, 32, 0),
                key(2),
                "high"
        );
        GACommitCommand<String> low = new GACommitCommand<>(
                new GABlockPosition(0, 0, 0),
                key(1),
                "low"
        );
        GACommitCommand<String> middleLate = new GACommitCommand<>(
                new GABlockPosition(0, 16, 0),
                key(4),
                "middle-late"
        );
        GACommitCommand<String> middleEarly = new GACommitCommand<>(
                new GABlockPosition(0, 16, 0),
                key(3),
                "middle-early"
        );
        List<String> replayed = new ArrayList<>();

        GACommitReplayPlan<String> replay = GACommitEngine.replayBySection(
                List.of(high, middleLate, low, middleEarly),
                command -> replayed.add(command.value())
        );

        assertEquals(List.of("low", "middle-early", "high"), replayed);
        assertEquals(3, replay.groups().size());
        assertEquals(new GACommitBatchStats(4, 3, 1, 1), replay.stats().batchStats());
        assertEquals(new GACommitMetrics(3, 4, 3, 1, 1, replay.metrics().executionNanos(), 0),
                GACommitMetrics.snapshotGlobalMetrics());
    }

    @Test
    void replayFailureMetricsAreAggregated() {
        GACommitMetrics.resetGlobal();
        GACommitCommand<String> ok = command(0, 0, "ok");
        GACommitCommand<String> fail = command(16, 1, "fail");

        GACommitReplayPlan<String> replay = GACommitEngine.replayByChunk(
                List.of(fail, ok),
                command -> {
                    if ("fail".equals(command.value())) {
                        throw new IllegalStateException("boom");
                    }
                }
        );

        assertEquals(1, replay.failures().size());
        assertEquals(fail, replay.failures().get(0).command());
        assertEquals(new GACommitMetrics(2, 2, 2, 0, 0, replay.metrics().executionNanos(), 1),
                replay.metrics());
        assertEquals(replay.metrics(), GACommitMetrics.snapshotGlobalMetrics());
    }

    private static List<String> replayValues(List<GACommitCommand<String>> commands) {
        List<String> values = new ArrayList<>();
        GACommitEngine.execute(
                GACommitBatch.of(commands),
                GACommitCollisionPolicy.FIRST_WRITE_WINS,
                command -> values.add(command.value())
        );
        return values;
    }

    private static GACommitCommand<String> command(int blockX, long sequence, String value) {
        return new GACommitCommand<>(new GABlockPosition(blockX, 64, 0), key(sequence), value);
    }

    private static GACommitOrderKey key(long sequence) {
        return new GACommitOrderKey(0, 0, 0, 0, 0, 0, (int) sequence, sequence);
    }
}
