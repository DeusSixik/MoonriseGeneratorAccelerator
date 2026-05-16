package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockPosition;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockWriteValue;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCommand;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GATransactionSandboxWriterTest {
    @Test
    void recordsWritesPostprocessAndTicks() {
        GATransactionSandboxWriter writer = new GATransactionSandboxWriter();

        assertTrue(writer.setBlock(1, 2, 3, "stone", 2));
        assertTrue(writer.markPostprocess(4, 5, 6));
        assertTrue(writer.scheduleFluidTick(7, 8, 9, "water", 10, 1));
        assertTrue(writer.scheduleBlockTick(10, 11, 12, "sapling", 20, 3));

        GATransactionSnapshot snapshot = writer.snapshot();

        assertEquals(GATransactionState.OPEN, snapshot.state());
        assertEquals(List.of(0L, 1L, 2L, 3L), snapshot.entries().stream().map(GAJournalEntry::sequence).toList());
        GAJournalEntry.BlockWrite blockWrite = assertInstanceOf(GAJournalEntry.BlockWrite.class, snapshot.entries().get(0));
        assertEquals(new GABlockMutation(1, 2, 3, "stone", 2, 0L), blockWrite.mutation());
        assertEquals(new GAJournalEntry.PostprocessMark(4, 5, 6, 1L), snapshot.entries().get(1));
        assertEquals(new GAJournalEntry.FluidTick(7, 8, 9, "water", 10, 1, 2L), snapshot.entries().get(2));
        assertEquals(new GAJournalEntry.BlockTick(10, 11, 12, "sapling", 20, 3, 3L), snapshot.entries().get(3));
    }

    @Test
    void unsupportedWriteMakesTerminalAndRejectsLaterWritesWithoutThrowing() {
        GATransactionSandboxWriter writer = new GATransactionSandboxWriter();

        assertTrue(writer.setBlock(0, 0, 0, "stone", 0));
        assertTrue(writer.unsupportedWrite("block entity write"));
        assertFalse(writer.setBlock(1, 1, 1, "dirt", 0));
        assertFalse(writer.markPostprocess(2, 2, 2));
        assertFalse(writer.unsupportedRead("already closed"));

        GATransactionSnapshot snapshot = writer.snapshot();

        assertEquals(GATransactionState.ABORTED, snapshot.state());
        assertEquals("unsupported write: block entity write", snapshot.reason());
        assertEquals(1, snapshot.entries().size());
    }

    @Test
    void unsupportedReadDowngradesAndRejectsLaterWritesWithoutThrowing() {
        GATransactionSandboxWriter writer = new GATransactionSandboxWriter();

        assertTrue(writer.unsupportedRead("world lookup"));
        assertFalse(writer.scheduleFluidTick(0, 0, 0, "water", 1, 0));

        GATransactionSnapshot snapshot = writer.snapshot();

        assertEquals(GATransactionState.DOWNGRADED, snapshot.state());
        assertEquals("unsupported read: world lookup", snapshot.reason());
        assertTrue(snapshot.entries().isEmpty());
    }

    @Test
    void failAbortsAndStoresExceptionClassInReason() {
        GATransactionSandboxWriter writer = new GATransactionSandboxWriter();

        assertTrue(writer.fail(new IllegalArgumentException("bad feature")));
        assertFalse(writer.scheduleBlockTick(0, 0, 0, "sapling", 1, 0));

        GATransactionSnapshot snapshot = writer.snapshot();

        assertEquals(GATransactionState.ABORTED, snapshot.state());
        assertTrue(snapshot.reason().contains(IllegalArgumentException.class.getName()));
        assertTrue(snapshot.reason().contains("bad feature"));
    }

    @Test
    void sealPathFeedsCommitBridge() {
        GATransactionSandboxWriter writer = new GATransactionSandboxWriter();
        writer.setBlock(1, 2, 3, "stone", 19);
        writer.markPostprocess(9, 9, 9);
        writer.setBlock(4, 5, 6, "dirt", 2);

        assertTrue(writer.seal());
        assertFalse(writer.seal());

        List<GACommitCommand<Object>> commands = GATransactionCommitBridge.blockWriteCommands(
                writer.snapshot(),
                baseKey()
        );

        assertEquals(2, commands.size());
        assertEquals(new GABlockPosition(1, 2, 3), commands.get(0).position());
        assertEquals(new GABlockWriteValue("stone", 19), commands.get(0).value());
        assertEquals(100_000_000L, commands.get(0).orderKey().sequence());
        assertEquals(new GABlockPosition(4, 5, 6), commands.get(1).position());
        assertEquals(new GABlockWriteValue("dirt", 2), commands.get(1).value());
        assertEquals(100_000_002L, commands.get(1).orderKey().sequence());
    }

    private static GACommitOrderKey baseKey() {
        return new GACommitOrderKey(5, 7, 1, -1, 3, 4, 11, 100L);
    }
}
