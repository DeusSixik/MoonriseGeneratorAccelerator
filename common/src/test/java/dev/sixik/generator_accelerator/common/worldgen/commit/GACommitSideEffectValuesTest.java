package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import dev.sixik.generator_accelerator.common.worldgen.transaction.GATransactionJournal;
import dev.sixik.generator_accelerator.common.worldgen.transaction.GATransactionSnapshot;

import java.util.List;

import static dev.sixik.generator_accelerator.common.worldgen.commit.GAScheduledTickValue.GAScheduledTickType.BLOCK;
import static dev.sixik.generator_accelerator.common.worldgen.commit.GAScheduledTickValue.GAScheduledTickType.FLUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GACommitSideEffectValuesTest {
    @Test
    void valuePlaceholdersCarryHeightmapPostprocessTickAndRepackPayloads() {
        assertEquals(72, new GAHeightmapUpdateValue("WORLD_SURFACE", 72, true).height());
        assertEquals(9L, new GAPostprocessMarkValue(9L).sourceSequence());
        assertEquals(BLOCK, new GAScheduledTickValue(BLOCK, "stone", 5, 1, 10L).type());
        assertEquals(3, new GAFinalRepackValue(3, 0x1L, 0x2L).sectionY());
    }

    @Test
    void sideEffectPlaceholdersRejectAmbiguousPayloads() {
        assertThrows(IllegalArgumentException.class, () -> new GAHeightmapUpdateValue(" ", 1, false));
        assertThrows(IllegalArgumentException.class, () -> new GAPostprocessMarkValue(-1L));
        assertThrows(IllegalArgumentException.class, () -> new GAScheduledTickValue(BLOCK, "stone", -1, 0, 0L));
        assertThrows(NullPointerException.class, () -> new GAScheduledTickValue(BLOCK, null, 0, 0, 0L));
    }

    @Test
    void bridgeConvertsSealedTransactionSideEffectsToCommitCommands() {
        GATransactionJournal journal = new GATransactionJournal();
        journal.appendBlockWrite(0, 64, 0, "stone", 3);
        journal.appendPostprocessMark(1, 65, 1);
        journal.appendFluidTick(2, 66, 2, "water", 5, 1);
        journal.appendBlockTick(3, 67, 3, "sand", 6, 2);
        journal.seal();
        GATransactionSnapshot snapshot = journal.snapshot();

        List<GACommitCommand<Object>> commands = GACommitSideEffectBridge.sideEffectCommands(snapshot, baseKey());

        assertEquals(3, commands.size());
        assertInstanceOf(GAPostprocessMarkValue.class, commands.get(0).value());
        GAScheduledTickValue fluid = assertInstanceOf(GAScheduledTickValue.class, commands.get(1).value());
        GAScheduledTickValue block = assertInstanceOf(GAScheduledTickValue.class, commands.get(2).value());
        assertEquals(FLUID, fluid.type());
        assertEquals(BLOCK, block.type());
        assertEquals(2_000_001L, commands.get(0).orderKey().sequence());
        assertEquals(2_000_003L, commands.get(2).orderKey().sequence());
    }

    private static GACommitOrderKey baseKey() {
        return new GACommitOrderKey(3, 4, 0, 0, 0, 0, 7, 2L);
    }
}
