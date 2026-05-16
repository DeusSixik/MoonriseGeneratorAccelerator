package dev.sixik.generator_accelerator.common.worldgen.transaction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GATransactionJournalTest {
    @Test
    void appendsCommandsInStableSequenceOrder() {
        GATransactionJournal journal = new GATransactionJournal();

        GABlockMutation first = journal.appendBlockWrite(1, 2, 3, "stone", 2);
        GAJournalEntry.PostprocessMark second = journal.appendPostprocessMark(4, 5, 6);
        GAJournalEntry.FluidTick third = journal.appendFluidTick(7, 8, 9, "water", 10, 1);
        GAJournalEntry.BlockTick fourth = journal.appendBlockTick(10, 11, 12, "sapling", 20, 3);

        GATransactionSnapshot snapshot = journal.snapshot();

        assertEquals(0L, first.sequence());
        assertEquals(1L, second.sequence());
        assertEquals(2L, third.sequence());
        assertEquals(3L, fourth.sequence());
        assertEquals(4L, snapshot.nextSequence());
        assertEquals(List.of(0L, 1L, 2L, 3L), snapshot.entries().stream().map(GAJournalEntry::sequence).toList());
        assertInstanceOf(GAJournalEntry.BlockWrite.class, snapshot.entries().get(0));
        assertInstanceOf(GAJournalEntry.PostprocessMark.class, snapshot.entries().get(1));
        assertInstanceOf(GAJournalEntry.FluidTick.class, snapshot.entries().get(2));
        assertInstanceOf(GAJournalEntry.BlockTick.class, snapshot.entries().get(3));
    }

    @Test
    void snapshotIsImmutableAndDetachedFromLaterAppends() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendBlockWrite(0, 1, 2, "stone", 0);

        GATransactionSnapshot snapshot = journal.snapshot();
        journal.appendBlockWrite(3, 4, 5, "dirt", 0);

        assertEquals(1, snapshot.entries().size());
        assertEquals(1, snapshot.blockMutations().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().add(
                new GAJournalEntry.PostprocessMark(0, 0, 0, 99L)
        ));
    }

    @Test
    void abortRejectsFurtherWritesAndKeepsReason() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendBlockWrite(0, 0, 0, "stone", 0);

        journal.abort("unsupported block entity write");

        assertEquals(GATransactionState.ABORTED, journal.state());
        assertEquals("unsupported block entity write", journal.reason());
        assertThrows(IllegalStateException.class, () -> journal.appendPostprocessMark(1, 1, 1));
        assertThrows(IllegalStateException.class, () -> journal.seal());
    }

    @Test
    void downgradeRejectsWritesAndStoresFallbackReason() {
        GATransactionJournal journal = new GATransactionJournal();

        journal.downgrade("cross-chunk write requires serial lane");

        assertEquals(GATransactionState.DOWNGRADED, journal.state());
        assertEquals("cross-chunk write requires serial lane", journal.reason());
        assertThrows(IllegalStateException.class, () -> journal.appendBlockWrite(0, 0, 0, "stone", 0));
    }

    @Test
    void sealRejectsFurtherWritesWithoutFallbackReason() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendBlockWrite(0, 0, 0, "stone", 0);

        journal.seal();

        assertEquals(GATransactionState.SEALED, journal.state());
        assertNull(journal.reason());
        assertThrows(IllegalStateException.class, () -> journal.appendFluidTick(0, 0, 0, "water", 1, 0));
    }

    @Test
    void clearReusesJournalAsFreshOpenTransaction() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendBlockWrite(0, 0, 0, "stone", 0);
        journal.abort("guard failed");

        journal.clear();
        GABlockMutation mutation = journal.appendBlockWrite(1, 2, 3, "dirt", 4);

        assertTrue(journal.open());
        assertEquals(GATransactionState.OPEN, journal.state());
        assertNull(journal.reason());
        assertEquals(1, journal.size());
        assertEquals(0L, mutation.sequence());
        assertEquals(1L, journal.snapshot().nextSequence());
    }

    @Test
    void terminalStateRequiresNonBlankReasonForAbortAndDowngrade() {
        assertThrows(IllegalArgumentException.class, () -> new GATransactionJournal().abort(""));
        assertThrows(IllegalArgumentException.class, () -> new GATransactionJournal().downgrade(" "));
    }
}
