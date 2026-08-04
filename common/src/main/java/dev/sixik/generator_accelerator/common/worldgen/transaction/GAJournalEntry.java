package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.Objects;

public sealed interface GAJournalEntry permits
        GAJournalEntry.BlockWrite,
        GAJournalEntry.PostprocessMark,
        GAJournalEntry.FluidTick,
        GAJournalEntry.BlockTick {
    long sequence();

    record BlockWrite(GABlockMutation mutation) implements GAJournalEntry {
        @Override
        public long sequence() {
            return mutation.sequence();
        }
    }

    record PostprocessMark(int x, int y, int z, long sequence) implements GAJournalEntry {
    }

    record FluidTick(int x, int y, int z, Object fluid, int delay, int priority, long sequence) implements GAJournalEntry {
    }

    record BlockTick(int x, int y, int z, Object block, int delay, int priority, long sequence) implements GAJournalEntry {
    }
}
