package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.worldgen.commit.CommitApplier;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitBatch;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionPolicy;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCommand;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitEngine;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitOrderKey;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitSideEffectBridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Success-only replay view for sealed transaction commands.
 */
public record GATransactionCommandJournal(List<GACommitCommand<Object>> commands) {
    public static GATransactionCommandJournal fromSuccessfulRun(
            GATransactionRunResult result,
            GACommitOrderKey baseOrderKey
    ) {
        Objects.requireNonNull(result, "result");
        if (!result.success()) {
            throw new IllegalStateException("transaction is not sealed success: " + result.snapshot().state());
        }
        return fromSealedSnapshot(result.snapshot(), baseOrderKey);
    }

    public static GATransactionCommandJournal fromSealedSnapshot(
            GATransactionSnapshot snapshot,
            GACommitOrderKey baseOrderKey
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(baseOrderKey, "baseOrderKey");
        if (snapshot.state() != GATransactionState.SEALED) {
            throw new IllegalStateException("transaction snapshot is " + snapshot.state());
        }

        List<GACommitCommand<Object>> commands = new ArrayList<>(snapshot.entries().size());
        commands.addAll(GATransactionCommitBridge.blockWriteCommands(snapshot, baseOrderKey));
        commands.addAll(GACommitSideEffectBridge.sideEffectCommands(snapshot, baseOrderKey));
        if (commands.size() > 1) {
            commands.sort(Comparator.comparing(GACommitCommand<Object>::orderKey));
        }
        return new GATransactionCommandJournal(commands);
    }

    public GACommitEngine.GACommitExecution<Object> replay(CommitApplier<Object> applier) {
        return replay(GACommitCollisionPolicy.FIRST_WRITE_WINS, applier);
    }

    public GACommitEngine.GACommitExecution<Object> replay(
            GACommitCollisionPolicy policy,
            CommitApplier<Object> applier
    ) {
        return GACommitEngine.execute(GACommitBatch.of(commands), policy, applier);
    }
}
