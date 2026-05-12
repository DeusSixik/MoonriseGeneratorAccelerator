package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockPosition;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockWriteValue;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionPolicy;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionResolver;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionResult;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCommand;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitOrderKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapts sealed transaction block writes into deterministic commit commands.
 *
 * <p>Base order sequence is treated as transaction ordinal; mutation sequence is
 * packed into a fixed child range so different transactions remain ordered.
 */
public final class GATransactionCommitBridge {
    public static final long ORDER_SEQUENCE_STRIDE = 1_000_000L;

    private GATransactionCommitBridge() {
    }

    public static GACommitCollisionResult<Object> resolveBlockWrites(
            GATransactionSnapshot snapshot,
            GACommitOrderKey baseOrderKey,
            GACommitCollisionPolicy collisionPolicy
    ) {
        return GACommitCollisionResolver.resolve(
                blockWriteCommands(snapshot, baseOrderKey),
                collisionPolicy
        );
    }

    public static List<GACommitCommand<Object>> blockWriteCommands(
            GATransactionSnapshot snapshot,
            GACommitOrderKey baseOrderKey
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(baseOrderKey, "baseOrderKey");
        requireSealed(snapshot);

        List<GACommitCommand<Object>> commands = new ArrayList<>(snapshot.entries().size());
        for (GAJournalEntry entry : snapshot.entries()) {
            if (!(entry instanceof GAJournalEntry.BlockWrite blockWrite)) {
                continue;
            }
            GABlockMutation mutation = blockWrite.mutation();
            commands.add(new GACommitCommand<>(
                    new GABlockPosition(mutation.x(), mutation.y(), mutation.z()),
                    orderKey(baseOrderKey, mutation),
                    new GABlockWriteValue(mutation.state(), mutation.flags())
            ));
        }
        return List.copyOf(commands);
    }

    private static void requireSealed(GATransactionSnapshot snapshot) {
        if (snapshot.state() != GATransactionState.SEALED) {
            throw new IllegalStateException("transaction snapshot is " + snapshot.state());
        }
    }

    private static GACommitOrderKey orderKey(GACommitOrderKey baseOrderKey, GABlockMutation mutation) {
        return new GACommitOrderKey(
                baseOrderKey.phase(),
                baseOrderKey.step(),
                baseOrderKey.chunkX(),
                baseOrderKey.chunkZ(),
                baseOrderKey.unitX(),
                baseOrderKey.unitZ(),
                baseOrderKey.localIndex(),
                childSequence(baseOrderKey, mutation)
        );
    }

    private static long childSequence(GACommitOrderKey baseOrderKey, GABlockMutation mutation) {
        long mutationSequence = mutation.sequence();
        if (mutationSequence < 0L || mutationSequence >= ORDER_SEQUENCE_STRIDE) {
            throw new IllegalArgumentException("transaction mutation sequence out of range: " + mutationSequence);
        }
        return Math.addExact(Math.multiplyExact(baseOrderKey.sequence(), ORDER_SEQUENCE_STRIDE), mutationSequence);
    }
}
