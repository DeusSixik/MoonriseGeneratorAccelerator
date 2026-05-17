package dev.sixik.generator_accelerator.common.worldgen.workspace;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GADecorationWriteJournalTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void journalKeepsLastWriteForDuplicatePosition() {
        GADecorationWriteJournal journal = new GADecorationWriteJournal();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState diamondOre = Blocks.DIAMOND_ORE.defaultBlockState();

        journal.add(4, 12, 7, Block.getId(stone));
        journal.add(4, 12, 7, Block.getId(diamondOre));

        assertEquals(1, journal.size());
        assertEquals(BlockPos.asLong(4, 12, 7), journal.packedPosition(0));
        assertEquals(diamondOre, journal.state(0));
        assertEquals(Block.getId(diamondOre), journal.blockIdAt(4, 12, 7));
    }

    @Test
    void journalContextRestoresPreviousBinding() {
        GADecorationWriteJournal outer = new GADecorationWriteJournal();
        GADecorationWriteJournal inner = new GADecorationWriteJournal();

        try (GADecorationJournalContext.Scope ignored = GADecorationJournalContext.bind(outer)) {
            assertSame(outer, GADecorationJournalContext.current());
            try (GADecorationJournalContext.Scope nested = GADecorationJournalContext.bind(inner)) {
                assertSame(inner, GADecorationJournalContext.current());
            }
            assertSame(outer, GADecorationJournalContext.current());
        }

        assertNull(GADecorationJournalContext.current());
    }
}
