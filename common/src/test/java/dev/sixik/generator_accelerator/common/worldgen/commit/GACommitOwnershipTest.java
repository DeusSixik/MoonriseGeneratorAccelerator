package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACommitOwnershipTest {
    @Test
    void splitKeepsOwnedCommandsAndGroupsForwardedTargetsDeterministically() {
        GAChunkPosition owner = new GAChunkPosition(0, 0);
        GACommitCommand<String> ownedLate = command(0, 3, "owned-late");
        GACommitCommand<String> forwardedPlus = command(16, 2, "plus");
        GACommitCommand<String> ownedEarly = command(0, 1, "owned-early");
        GACommitCommand<String> forwardedMinus = command(-16, 0, "minus");

        GACommitOwnership.GACommitOwnershipSplit<String> split = GACommitOwnership.split(
                owner,
                List.of(ownedLate, forwardedPlus, ownedEarly, forwardedMinus)
        );

        assertEquals(List.of(ownedEarly, ownedLate), split.owned());
        assertEquals(2, split.forwardedCount());
        assertEquals(List.of(new GAChunkPosition(-1, 0), new GAChunkPosition(1, 0)),
                List.copyOf(split.forwardedByTarget().keySet()));
        assertEquals(List.of(forwardedMinus), split.forwardedByTarget().get(new GAChunkPosition(-1, 0)));
        assertEquals(List.of(forwardedPlus), split.forwardedByTarget().get(new GAChunkPosition(1, 0)));
    }

    @Test
    void drainOwnedCombinesLocalAndMailboxBeforeCollisionResolution() {
        GAChunkPosition owner = new GAChunkPosition(0, 0);
        GACommitCommand<String> localLate = command(0, 2, "local-late");
        GACommitCommand<String> mailboxEarly = command(0, 1, "mailbox-early");
        GACommitMailbox<String> mailbox = new GACommitMailbox<>();
        mailbox.enqueue(mailboxEarly);
        mailbox.enqueue(command(16, 3, "foreign"));

        GACommitBatch.GAResolvedCommitBatch<String> drained = GACommitOwnership.drainOwned(
                owner,
                List.of(localLate),
                mailbox,
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        );

        assertEquals(List.of(mailboxEarly), drained.accepted());
        assertEquals(List.of(localLate), drained.rejected());
        assertFalse(mailbox.hasQueued(owner));
        assertTrue(mailbox.hasQueued(new GAChunkPosition(1, 0)));
    }

    @Test
    void drainOwnedFailsFastWhenLocalCommandsTargetAnotherChunk() {
        assertThrows(IllegalArgumentException.class, () -> GACommitOwnership.drainOwned(
                new GAChunkPosition(0, 0),
                List.of(command(16, 0, "foreign")),
                new GACommitMailbox<>(),
                GACommitCollisionPolicy.FIRST_WRITE_WINS
        ));
    }

    private static GACommitCommand<String> command(int blockX, long sequence, String value) {
        return new GACommitCommand<>(
                new GABlockPosition(blockX, 64, 0),
                new GACommitOrderKey(0, 0, blockX >> 4, 0, blockX >> 4, 0, (int) sequence, sequence),
                value
        );
    }
}
