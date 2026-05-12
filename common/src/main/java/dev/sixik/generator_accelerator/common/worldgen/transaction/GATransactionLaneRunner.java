package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Runs one selected unknown unit against a detached transaction context.
 */
public final class GATransactionLaneRunner {
    private GATransactionLaneRunner() {
    }

    public static GATransactionRunResult run(String unitId, GATransactionSandboxUnit unit) {
        Objects.requireNonNull(unit, "unit");
        GATransactionSandboxWriter writer = new GATransactionSandboxWriter();
        GATransactionSandboxContext context = new GATransactionSandboxContext(unitId, writer);

        try {
            unit.run(context);
            if (writer.journal().open()) {
                writer.seal();
            }
        } catch (Exception exception) {
            writer.fail(exception);
        }

        GATransactionSnapshot snapshot = writer.snapshot();
        return new GATransactionRunResult(
                context.unitId(),
                snapshot,
                GATransactionHandoffMetadata.fromSnapshot(context.unitId(), snapshot)
        );
    }

    public static CompletableFuture<GATransactionRunResult> runAsync(String unitId, GATransactionSandboxUnit unit) {
        return GAScheduler.supplyAsync(GAScheduler.Lane.TRANSACTIONAL, () -> run(unitId, unit));
    }
}
