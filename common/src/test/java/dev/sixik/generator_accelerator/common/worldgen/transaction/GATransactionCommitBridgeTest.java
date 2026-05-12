package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockPosition;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockWriteValue;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionPolicy;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionResult;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCommand;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GATransactionCommitBridgeTest {
    @Test
    void sealedTransactionConvertsOnlyBlockWritesIntoCommitCommands() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendPostprocessMark(9, 9, 9);
        journal.appendBlockWrite(1, 2, 3, "stone", 2);
        journal.appendFluidTick(4, 5, 6, "water", 1, 0);
        journal.appendBlockWrite(7, 8, 9, "dirt", 4);
        journal.seal();

        List<GACommitCommand<Object>> commands = GATransactionCommitBridge.blockWriteCommands(
                journal.snapshot(),
                baseKey()
        );

        assertEquals(2, commands.size());
        assertEquals(new GABlockPosition(1, 2, 3), commands.get(0).position());
        assertEquals(new GABlockPosition(7, 8, 9), commands.get(1).position());
        assertEquals(100_000_001L, commands.get(0).orderKey().sequence());
        assertEquals(100_000_003L, commands.get(1).orderKey().sequence());
        assertEquals(new GABlockWriteValue("stone", 2), commands.get(0).value());
        assertEquals(new GABlockWriteValue("dirt", 4), commands.get(1).value());
    }

    @Test
    void firstWriteWinsCollisionIsDeterministicByMutationSequence() {
        GATransactionSnapshot snapshot = sealedSnapshot(
                new GAJournalEntry.BlockWrite(new GABlockMutation(1, 2, 3, "late", 8, 6L)),
                new GAJournalEntry.BlockWrite(new GABlockMutation(1, 2, 3, "early", 4, 2L)),
                new GAJournalEntry.BlockWrite(new GABlockMutation(1, 2, 3, "middle", 6, 4L))
        );

        GACommitCollisionResult<Object> result = GATransactionCommitBridge.resolveBlockWrites(
                snapshot,
                baseKey(),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );

        assertEquals(1, result.accepted().size());
        assertEquals(new GABlockWriteValue("early", 4), result.accepted().get(0).value());
        assertEquals(List.of(
                new GABlockWriteValue("middle", 6),
                new GABlockWriteValue("late", 8)
        ), result.rejected().stream().map(GACommitCommand::value).toList());
    }

    @Test
    void openAbortedAndDowngradedSnapshotsAreRejected() {
        assertThrows(IllegalStateException.class, () -> GATransactionCommitBridge.resolveBlockWrites(
                snapshotWithState(GATransactionState.OPEN),
                baseKey(),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        ));
        assertThrows(IllegalStateException.class, () -> GATransactionCommitBridge.resolveBlockWrites(
                snapshotWithState(GATransactionState.ABORTED),
                baseKey(),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        ));
        assertThrows(IllegalStateException.class, () -> GATransactionCommitBridge.resolveBlockWrites(
                snapshotWithState(GATransactionState.DOWNGRADED),
                baseKey(),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        ));
    }

    @Test
    void blockWriteFlagsArePreservedInCommitValue() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendBlockWrite(1, 2, 3, "state", 19);
        journal.seal();

        Object value = GATransactionCommitBridge.resolveBlockWrites(
                journal.snapshot(),
                baseKey(),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        ).accepted().get(0).value();

        GABlockWriteValue blockWrite = assertInstanceOf(GABlockWriteValue.class, value);
        assertEquals("state", blockWrite.state());
        assertEquals(19, blockWrite.flags());
    }

    @Test
    void childOrderKeepsBaseSequenceBeforeMutationSequence() {
        GATransactionSnapshot firstSnapshot = sealedSnapshot(
                new GAJournalEntry.BlockWrite(new GABlockMutation(1, 2, 3, "first", 1, 999_999L))
        );
        GATransactionSnapshot secondSnapshot = sealedSnapshot(
                new GAJournalEntry.BlockWrite(new GABlockMutation(1, 2, 3, "second", 1, 0L))
        );

        GACommitOrderKey first = new GACommitOrderKey(5, 7, 1, -1, 3, 4, 11, 10L);
        GACommitOrderKey second = new GACommitOrderKey(5, 7, 1, -1, 3, 4, 11, 11L);

        GACommitOrderKey firstChild = GATransactionCommitBridge.blockWriteCommands(firstSnapshot, first).get(0).orderKey();
        GACommitOrderKey secondChild = GATransactionCommitBridge.blockWriteCommands(secondSnapshot, second).get(0).orderKey();

        assertTrue(firstChild.compareTo(secondChild) < 0);
    }

    @Test
    void mutationSequenceOutsideStrideIsRejected() {
        GATransactionSnapshot snapshot = sealedSnapshot(
                new GAJournalEntry.BlockWrite(new GABlockMutation(1, 2, 3, "state", 1, GATransactionCommitBridge.ORDER_SEQUENCE_STRIDE))
        );

        assertThrows(IllegalArgumentException.class, () -> GATransactionCommitBridge.blockWriteCommands(snapshot, baseKey()));
    }

    @Test
    void rejectPolicyReturnsOnlyBlockWriteCollisions() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendBlockWrite(1, 2, 3, "stone", 1);
        journal.appendPostprocessMark(1, 2, 3);
        journal.appendBlockWrite(1, 2, 3, "dirt", 2);
        journal.seal();

        GACommitCollisionResult<Object> result = GATransactionCommitBridge.resolveBlockWrites(
                journal.snapshot(),
                baseKey(),
                GACommitCollisionPolicy.REJECT
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(2, result.rejected().size());
    }

    private static GATransactionSnapshot snapshotWithState(GATransactionState state) {
        return new GATransactionSnapshot(state, "terminal", List.of(), 0L);
    }

    private static GATransactionSnapshot sealedSnapshot(GAJournalEntry... entries) {
        return new GATransactionSnapshot(GATransactionState.SEALED, null, List.of(entries), 7L);
    }

    private static GACommitOrderKey baseKey() {
        return new GACommitOrderKey(5, 7, 1, -1, 3, 4, 11, 100L);
    }
}
