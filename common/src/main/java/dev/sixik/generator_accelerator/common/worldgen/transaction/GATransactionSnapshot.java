package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.List;
import java.util.Objects;

public record GATransactionSnapshot(
        GATransactionState state,
        String reason,
        List<GAJournalEntry> entries,
        long nextSequence
) {
    public GATransactionSnapshot {
        Objects.requireNonNull(state, "state");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public List<GABlockMutation> blockMutations() {
        return entries.stream()
                .filter(GAJournalEntry.BlockWrite.class::isInstance)
                .map(GAJournalEntry.BlockWrite.class::cast)
                .map(GAJournalEntry.BlockWrite::mutation)
                .toList();
    }
}
