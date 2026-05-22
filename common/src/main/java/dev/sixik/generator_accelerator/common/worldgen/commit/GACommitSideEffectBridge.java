package dev.sixik.generator_accelerator.common.worldgen.commit;

import dev.sixik.generator_accelerator.common.worldgen.transaction.GAJournalEntry;
import dev.sixik.generator_accelerator.common.worldgen.transaction.GATransactionCommitBridge;
import dev.sixik.generator_accelerator.common.worldgen.transaction.GATransactionSnapshot;
import dev.sixik.generator_accelerator.common.worldgen.transaction.GATransactionState;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static dev.sixik.generator_accelerator.common.worldgen.commit.GAScheduledTickValue.GAScheduledTickType.BLOCK;
import static dev.sixik.generator_accelerator.common.worldgen.commit.GAScheduledTickValue.GAScheduledTickType.FLUID;

/**
 * Adapts sealed transaction side effects into detached owner-commit commands.
 */
public final class GACommitSideEffectBridge {
    private GACommitSideEffectBridge() {
    }

    public static List<GACommitCommand<Object>> sideEffectCommands(
            GATransactionSnapshot snapshot,
            GACommitOrderKey baseOrderKey
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(baseOrderKey, "baseOrderKey");
        if (snapshot.state() != GATransactionState.SEALED) {
            throw new IllegalStateException("transaction snapshot is " + snapshot.state());
        }

        List<GACommitCommand<Object>> commands = new ObjectArrayList<>(snapshot.entries().size());
        for (GAJournalEntry entry : snapshot.entries()) {
            if (entry instanceof GAJournalEntry.PostprocessMark mark) {
                commands.add(command(
                    mark.x(),
                    mark.y(),
                    mark.z(),
                    baseOrderKey,
                    mark.sequence(),
                    new GAPostprocessMarkValue(mark.sequence())
                ));
            } else if (entry instanceof GAJournalEntry.FluidTick tick) {
                commands.add(command(
                    tick.x(),
                    tick.y(),
                    tick.z(),
                    baseOrderKey,
                    tick.sequence(),
                    new GAScheduledTickValue(FLUID, tick.fluid(), tick.delay(), tick.priority(), tick.sequence())
                ));
            } else if (entry instanceof GAJournalEntry.BlockTick tick) {
                commands.add(command(
                    tick.x(),
                    tick.y(),
                    tick.z(),
                    baseOrderKey,
                    tick.sequence(),
                    new GAScheduledTickValue(BLOCK, tick.block(), tick.delay(), tick.priority(), tick.sequence())
                ));
            }
        }
        return List.copyOf(commands);
    }

    private static GACommitCommand<Object> command(
            int x,
            int y,
            int z,
            GACommitOrderKey baseOrderKey,
            long sourceSequence,
            Object value
    ) {
        return new GACommitCommand<>(
                new GABlockPosition(x, y, z),
                orderKey(baseOrderKey, sourceSequence),
                value
        );
    }

    private static GACommitOrderKey orderKey(GACommitOrderKey baseOrderKey, long sourceSequence) {
        if (sourceSequence < 0L || sourceSequence >= GATransactionCommitBridge.ORDER_SEQUENCE_STRIDE) {
            throw new IllegalArgumentException("transaction side-effect sequence out of range: " + sourceSequence);
        }
        return new GACommitOrderKey(
                baseOrderKey.phase(),
                baseOrderKey.step(),
                baseOrderKey.chunkX(),
                baseOrderKey.chunkZ(),
                baseOrderKey.unitX(),
                baseOrderKey.unitZ(),
                baseOrderKey.localIndex(),
                Math.addExact(Math.multiplyExact(
                        baseOrderKey.sequence(),
                        GATransactionCommitBridge.ORDER_SEQUENCE_STRIDE
                ), sourceSequence)
        );
    }
}
