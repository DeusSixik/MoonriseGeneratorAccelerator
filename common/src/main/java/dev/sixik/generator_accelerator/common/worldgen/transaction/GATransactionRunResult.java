package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.Objects;

public record GATransactionRunResult(
        String unitId,
        GATransactionSnapshot snapshot,
        GATransactionHandoffMetadata handoff
) {
    public GATransactionRunResult {
        unitId = unitId == null || unitId.isBlank() ? "unknown" : unitId.trim();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(handoff, "handoff");
    }

    public boolean success() {
        return snapshot.state() == GATransactionState.SEALED
                && handoff.action() == GATransactionHandoffAction.NONE;
    }
}
