package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockPosition;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockWriteValue;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCommand;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitOrderKey;
import dev.sixik.generator_accelerator.common.worldgen.commit.GAPostprocessMarkValue;
import dev.sixik.generator_accelerator.common.worldgen.commit.GAScheduledTickValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.sixik.generator_accelerator.common.worldgen.commit.GAScheduledTickValue.GAScheduledTickType.BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GATransactionLaneRunnerTest {
    @AfterEach
    void shutdownScheduler() {
        GAScheduler.shutdownForTests();
    }

    @Test
    void successfulUnitSealsSnapshotAndBuildsReplayJournal() {
        GATransactionRunResult result = GATransactionLaneRunner.run("ore-vein", context -> {
            assertEquals("ore-vein", context.unitId());
            assertTrue(context.setBlock(1, 2, 3, "stone", 19));
            assertTrue(context.markPostprocess(4, 5, 6));
            assertTrue(context.scheduleBlockTick(7, 8, 9, "sapling", 2, 1));
        });

        assertTrue(result.success());
        assertEquals(GATransactionState.SEALED, result.snapshot().state());
        assertEquals(GATransactionHandoffAction.NONE, result.handoff().action());
        assertFalse(result.handoff().requiresSerialFallback());
        assertFalse(result.handoff().requiresQuarantine());
        assertNull(result.handoff().reason());

        GATransactionCommandJournal journal = GATransactionCommandJournal.fromSuccessfulRun(result, baseKey());

        assertEquals(3, journal.commands().size());
        assertEquals(List.of(100_000_000L, 100_000_001L, 100_000_002L),
                journal.commands().stream().map(command -> command.orderKey().sequence()).toList());
        assertEquals(new GABlockPosition(1, 2, 3), journal.commands().get(0).position());
        assertEquals(new GABlockWriteValue("stone", 19), journal.commands().get(0).value());
        assertInstanceOf(GAPostprocessMarkValue.class, journal.commands().get(1).value());
        GAScheduledTickValue tick = assertInstanceOf(GAScheduledTickValue.class, journal.commands().get(2).value());
        assertEquals(BLOCK, tick.type());
    }

    @Test
    void replayAppliesOnlyAfterSealedSuccess() {
        GATransactionRunResult result = GATransactionLaneRunner.run("lake", context -> {
            context.setBlock(1, 2, 3, "water", 2);
            context.setBlock(1, 2, 3, "lava", 2);
        });
        GATransactionCommandJournal journal = GATransactionCommandJournal.fromSuccessfulRun(result, baseKey());
        List<GACommitCommand<Object>> applied = new ArrayList<>();

        var execution = journal.replay(applied::add);

        assertEquals(1, applied.size());
        assertEquals(new GABlockWriteValue("water", 2), applied.get(0).value());
        assertEquals(2, execution.resolved().stats().inputCount());
        assertEquals(1, execution.resolved().stats().rejectedCount());
        assertTrue(execution.failures().isEmpty());
    }

    @Test
    void downgradedUnitCarriesSerialFallbackMetadataAndCannotCommit() {
        GATransactionRunResult result = GATransactionLaneRunner.run("tree", context -> {
            context.setBlock(0, 0, 0, "log", 0);
            assertTrue(context.unsupportedRead("neighbor chunk lookup"));
            assertFalse(context.setBlock(0, 1, 0, "leaf", 0));
        });

        assertFalse(result.success());
        assertEquals(GATransactionState.DOWNGRADED, result.snapshot().state());
        assertEquals(GATransactionHandoffAction.SERIAL_FALLBACK, result.handoff().action());
        assertEquals("unsupported read: neighbor chunk lookup", result.handoff().reason());
        assertTrue(result.handoff().requiresSerialFallback());
        assertFalse(result.handoff().requiresQuarantine());
        assertThrows(IllegalStateException.class, () -> GATransactionCommandJournal.fromSuccessfulRun(result, baseKey()));
    }

    @Test
    void failingUnitAbortsAndProducesQuarantineHandoff() {
        GATransactionRunResult result = GATransactionLaneRunner.run("feature-instance-7", context -> {
            context.setBlock(0, 0, 0, "ore", 0);
            throw new IllegalArgumentException("bad density");
        });

        assertFalse(result.success());
        assertEquals(GATransactionState.ABORTED, result.snapshot().state());
        assertEquals(GATransactionHandoffAction.QUARANTINE_AND_SERIAL_FALLBACK, result.handoff().action());
        assertTrue(result.handoff().requiresSerialFallback());
        assertTrue(result.handoff().requiresQuarantine());
        assertEquals(IllegalArgumentException.class.getName(), result.handoff().exceptionClass());
        assertTrue(result.handoff().quarantineKey().contains("feature-instance-7"));
        assertTrue(result.handoff().reason().contains("bad density"));
    }

    @Test
    void unsupportedWriteAbortsWithoutThrowingThroughContext() {
        GATransactionRunResult result = GATransactionLaneRunner.run("block-entity", context -> {
            assertTrue(context.unsupportedWrite("block entity mutation"));
            assertFalse(context.markPostprocess(1, 2, 3));
        });

        assertEquals(GATransactionState.ABORTED, result.snapshot().state());
        assertEquals("unsupported write: block entity mutation", result.handoff().reason());
        assertTrue(result.handoff().requiresQuarantine());
    }

    @Test
    void asyncRunUsesTransactionalSchedulerLane() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();

        GATransactionRunResult result = GATransactionLaneRunner.runAsync("async-ore", context -> {
            threadName.set(Thread.currentThread().getName());
            assertTrue(context.setBlock(1, 2, 3, "ore", 2));
        }).get(10, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertTrue(threadName.get().startsWith("GA-TRANSACTIONAL-"), threadName.get());
    }

    private static GACommitOrderKey baseKey() {
        return new GACommitOrderKey(5, 7, 1, -1, 3, 4, 11, 100L);
    }
}
