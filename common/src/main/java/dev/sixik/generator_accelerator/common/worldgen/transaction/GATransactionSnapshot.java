package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;

public record GATransactionSnapshot(
        GATransactionState state,
        String reason,
        List<GAJournalEntry> entries,
        long nextSequence
) {
    public List<GABlockMutation> blockMutations() {
        if (entries.isEmpty()) {
            return List.of();
        }
        ArrayList<GABlockMutation> mutations = new ArrayList<>();
        for (GAJournalEntry entry : entries) {
            if (entry instanceof GAJournalEntry.BlockWrite blockWrite) {
                mutations.add(blockWrite.mutation());
            }
        }
        return mutations.isEmpty() ? List.of() : List.copyOf(mutations);
    }
}
