package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.sixik.generator_accelerator.common.worldgen.commit.GACommitConflictGroups.GACommitConflictGranularity.CHUNK;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GACommitFinalizePlanTest {
    @Test
    void finalizePlanReplaysAcceptedCommandsInGlobalOrder() {
        GACommitMetrics.resetGlobal();
        GACommitCommand<String> lateCollision = command(0, 4, "late-collision");
        GACommitCommand<String> earlyCollision = command(0, 1, "early-collision");
        GACommitCommand<String> middle = command(16, 2, "middle");
        GACommitCommand<String> last = command(32, 3, "last");
        GACommitPlan<String> plan = GACommitEngine.plan(
                GACommitBatch.of(List.of(lateCollision, last, middle, earlyCollision)),
                GACommitCollisionPolicy.FIRST_WRITE_WINS,
                CHUNK
        );

        GACommitFinalizePlan<String> finalizePlan = GACommitEngine.finalizePlan(plan);
        List<String> replayed = new ArrayList<>();
        GACommitEngine.GACommitExecution<String> execution = GACommitEngine.replayFinalized(
                finalizePlan,
                command -> replayed.add(command.value())
        );

        assertEquals(List.of(earlyCollision, middle, last), finalizePlan.replayCommands());
        assertEquals(List.of("early-collision", "middle", "last"), replayed);
        assertEquals(new GACommitBatchStats(4, 3, 1, 1), finalizePlan.stats());
        assertEquals(finalizePlan.stats(), execution.resolved().stats());
        assertEquals(new GACommitMetrics(1, 4, 3, 1, 1, execution.metrics().executionNanos(), 0),
                GACommitMetrics.snapshotGlobalMetrics());
    }

    private static GACommitCommand<String> command(int blockX, long sequence, String value) {
        return new GACommitCommand<>(new GABlockPosition(blockX, 64, 0), key(sequence), value);
    }

    private static GACommitOrderKey key(long sequence) {
        return new GACommitOrderKey(0, 0, 0, 0, 0, 0, (int) sequence, sequence);
    }
}
