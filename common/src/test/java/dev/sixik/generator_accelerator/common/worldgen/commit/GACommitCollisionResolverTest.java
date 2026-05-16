package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACommitCollisionResolverTest {
    @Test
    void firstWriteWinsIsStableRegardlessInputOrder() {
        GACommitCommand<String> early = command(0, "early");
        GACommitCommand<String> middle = command(1, "middle");
        GACommitCommand<String> late = command(2, "late");
        List<GACommitCommand<String>> commands = List.of(late, early, middle);

        GACommitCollisionResult<String> resultA = GACommitCollisionResolver.resolve(
                commands,
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );
        List<GACommitCommand<String>> reversed = new ArrayList<>(commands);
        Collections.reverse(reversed);
        GACommitCollisionResult<String> resultB = GACommitCollisionResolver.resolve(
                reversed,
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );

        assertEquals(List.of(early), resultA.accepted());
        assertEquals(resultA.accepted(), resultB.accepted());
        assertEquals(List.of(middle, late), resultA.rejected());
        assertEquals(resultA.rejected(), resultB.rejected());
    }

    @Test
    void laterWriteWinsUsesHighestOrderKey() {
        GACommitCommand<String> early = command(0, "early");
        GACommitCommand<String> late = command(2, "late");

        GACommitCollisionResult<String> result = GACommitCollisionResolver.resolve(
                List.of(late, early),
                GACommitCollisionPolicy.LATER_WRITE_WINS
        );

        assertEquals(List.of(late), result.accepted());
        assertEquals(List.of(early), result.rejected());
    }

    @Test
    void rejectPolicyKeepsCollidingGroupOutOfAcceptedWrites() {
        GACommitCommand<String> early = command(0, "early");
        GACommitCommand<String> late = command(2, "late");

        GACommitCollisionResult<String> result = GACommitCollisionResolver.resolve(
                List.of(late, early),
                GACommitCollisionPolicy.REJECT
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(List.of(early, late), result.rejected());
    }

    @Test
    void duplicateTieIsHandledBySequenceInOrderKey() {
        GACommitCommand<String> sequenceZero = command(0, "zero");
        GACommitCommand<String> sequenceOne = command(1, "one");

        GACommitCollisionResult<String> result = GACommitCollisionResolver.resolve(
                List.of(sequenceOne, sequenceZero),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );

        assertEquals(List.of(sequenceZero), result.accepted());
        assertEquals(List.of(sequenceOne), result.rejected());
    }

    @Test
    void exactDuplicateKeyWithDifferentValueIsRejectedAsAmbiguous() {
        GACommitOrderKey key = key(0);
        GACommitCommand<String> first = new GACommitCommand<>(position(), key, "first");
        GACommitCommand<String> second = new GACommitCommand<>(position(), key, "second");

        assertThrows(IllegalArgumentException.class, () -> GACommitCollisionResolver.resolve(
                List.of(first, second),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        ));
    }

    private static GACommitCommand<String> command(long sequence, String value) {
        return new GACommitCommand<>(position(), key(sequence), value);
    }

    private static GABlockPosition position() {
        return new GABlockPosition(16, 64, -3);
    }

    private static GACommitOrderKey key(long sequence) {
        return new GACommitOrderKey(0, 2, 1, -1, 1, -1, 7, sequence);
    }
}
