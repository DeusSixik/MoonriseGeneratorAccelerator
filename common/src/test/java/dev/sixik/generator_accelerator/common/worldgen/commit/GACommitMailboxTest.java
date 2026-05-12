package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACommitMailboxTest {
    @Test
    void drainsOnlyRequestedTargetChunkWithPolicy() {
        GACommitCommand<String> chunkZeroEarly = command(0, 0, "zero-early");
        GACommitCommand<String> chunkZeroLate = command(0, 1, "zero-late");
        GACommitCommand<String> chunkOne = command(16, 2, "one");
        GACommitMailbox<String> mailbox = new GACommitMailbox<>();

        mailbox.enqueueAll(List.of(chunkOne, chunkZeroLate, chunkZeroEarly));
        GACommitBatch.GAResolvedCommitBatch<String> drainedZero = mailbox.drain(
                new GAChunkPosition(0, 0),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );

        assertEquals(List.of(chunkZeroEarly), drainedZero.accepted());
        assertEquals(List.of(chunkZeroLate), drainedZero.rejected());
        assertFalse(mailbox.hasQueued(new GAChunkPosition(0, 0)));
        assertTrue(mailbox.hasQueued(new GAChunkPosition(1, 0)));
        assertEquals(1, mailbox.queuedCommandCount(new GAChunkPosition(1, 0)));

        GACommitBatch.GAResolvedCommitBatch<String> drainedOne = mailbox.drain(
                new GAChunkPosition(1, 0),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );
        assertEquals(List.of(chunkOne), drainedOne.accepted());
        assertTrue(drainedOne.rejected().isEmpty());
    }

    @Test
    void drainAllVisitsEveryTargetInChunkOrder() {
        GACommitCommand<String> chunkMinus = command(-16, 0, "minus");
        GACommitCommand<String> chunkZeroLate = command(0, 2, "zero-late");
        GACommitCommand<String> chunkZeroEarly = command(0, 1, "zero-early");
        GACommitCommand<String> chunkOne = command(16, 3, "one");
        GACommitMailbox<String> mailbox = new GACommitMailbox<>();

        mailbox.enqueueAll(List.of(chunkOne, chunkZeroLate, chunkMinus, chunkZeroEarly));
        List<GACommitMailbox.GACommitMailboxDrain<String>> drained = mailbox.drainAll(
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );

        assertEquals(new GAChunkPosition(-1, 0), drained.get(0).targetChunk());
        assertEquals(new GAChunkPosition(0, 0), drained.get(1).targetChunk());
        assertEquals(new GAChunkPosition(1, 0), drained.get(2).targetChunk());
        assertEquals(List.of(chunkMinus), drained.get(0).resolved().accepted());
        assertEquals(List.of(chunkZeroEarly), drained.get(1).resolved().accepted());
        assertEquals(List.of(chunkZeroLate), drained.get(1).resolved().rejected());
        assertEquals(List.of(chunkOne), drained.get(2).resolved().accepted());
        assertFalse(mailbox.hasQueued(new GAChunkPosition(-1, 0)));
        assertFalse(mailbox.hasQueued(new GAChunkPosition(0, 0)));
        assertFalse(mailbox.hasQueued(new GAChunkPosition(1, 0)));
    }

    @Test
    void executeAllReportsAggregateMetrics() {
        GACommitMetrics.resetGlobal();
        GACommitCommand<String> ok = command(0, 0, "ok");
        GACommitCommand<String> fail = command(16, 1, "fail");
        GACommitMailbox<String> mailbox = new GACommitMailbox<>();
        mailbox.enqueueAll(List.of(fail, ok));

        GACommitMailbox.GACommitMailboxExecution<String> execution = mailbox.executeAll(
                GACommitCollisionPolicy.FIRST_WRITE_WINS,
                command -> {
                    if ("fail".equals(command.value())) {
                        throw new IllegalStateException("boom");
                    }
                }
        );

        assertEquals(2, execution.drained().size());
        assertEquals(1, execution.failures().size());
        assertEquals(new GACommitMetrics(2, 2, 2, 0, 0, execution.metrics().executionNanos(), 1),
                execution.metrics());
        assertEquals(execution.metrics(), GACommitMetrics.snapshotGlobalMetrics());
    }

    private static GACommitCommand<String> command(int blockX, long sequence, String value) {
        return new GACommitCommand<>(
                new GABlockPosition(blockX, 64, 0),
                new GACommitOrderKey(0, 0, blockX >> 4, 0, blockX >> 4, 0, (int) sequence, sequence),
                value
        );
    }
}
