package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACommitBatchTest {
    @Test
    void emptyBatchResolvesToEmptyStats() {
        GACommitBatch.GAResolvedCommitBatch<String> resolved = GACommitBatch.<String>empty()
                .resolve(GACommitCollisionPolicy.FIRST_WRITE_WINS);

        assertTrue(resolved.accepted().isEmpty());
        assertTrue(resolved.rejected().isEmpty());
        assertEquals(GACommitBatchStats.empty(), resolved.stats());
    }

    @Test
    void firstWriteWinsReportsCollisionStats() {
        GACommitCommand<String> early = command(position(1), 0, "early");
        GACommitCommand<String> late = command(position(1), 1, "late");
        GACommitCommand<String> solo = command(position(2), 2, "solo");

        GACommitBatch.GAResolvedCommitBatch<String> resolved = GACommitBatch.of(List.of(late, solo, early))
                .resolve(GACommitCollisionPolicy.FIRST_WRITE_WINS);

        assertEquals(List.of(early, solo), resolved.accepted());
        assertEquals(List.of(late), resolved.rejected());
        assertEquals(new GACommitBatchStats(3, 2, 1, 1), resolved.stats());
    }

    @Test
    void rejectPolicyReportsRejectedCollisionGroup() {
        GACommitCommand<String> early = command(position(1), 0, "early");
        GACommitCommand<String> late = command(position(1), 1, "late");

        GACommitBatch.GAResolvedCommitBatch<String> resolved = GACommitBatch.of(List.of(late, early))
                .resolve(GACommitCollisionPolicy.REJECT);

        assertTrue(resolved.accepted().isEmpty());
        assertEquals(List.of(early, late), resolved.rejected());
        assertEquals(new GACommitBatchStats(2, 0, 2, 1), resolved.stats());
    }

    @Test
    void inputOrderDoesNotChangeResolvedBatchOrMutateInput() {
        GACommitCommand<String> a = command(position(1), 0, "a");
        GACommitCommand<String> b = command(position(2), 1, "b");
        GACommitCommand<String> c = command(position(1), 2, "c");
        List<GACommitCommand<String>> input = new ArrayList<>(List.of(c, b, a));

        GACommitBatch<String> batch = GACommitBatch.of(input);
        GACommitBatch.GAResolvedCommitBatch<String> resolvedA = batch.resolve(GACommitCollisionPolicy.FIRST_WRITE_WINS);
        Collections.reverse(input);
        GACommitBatch.GAResolvedCommitBatch<String> resolvedB = GACommitBatch.of(input)
                .resolve(GACommitCollisionPolicy.FIRST_WRITE_WINS);

        assertEquals(List.of(c, b, a), batch.commands());
        assertEquals(List.of(a, b), resolvedA.accepted());
        assertEquals(resolvedA.accepted(), resolvedB.accepted());
        assertEquals(resolvedA.rejected(), resolvedB.rejected());
        assertEquals(resolvedA.stats(), resolvedB.stats());
    }

    private static GACommitCommand<String> command(GABlockPosition position, long sequence, String value) {
        return new GACommitCommand<>(position, key(sequence), value);
    }

    private static GABlockPosition position(int x) {
        return new GABlockPosition(x, 64, -3);
    }

    private static GACommitOrderKey key(long sequence) {
        return new GACommitOrderKey(0, 0, 0, 0, 0, 0, (int) sequence, sequence);
    }
}
