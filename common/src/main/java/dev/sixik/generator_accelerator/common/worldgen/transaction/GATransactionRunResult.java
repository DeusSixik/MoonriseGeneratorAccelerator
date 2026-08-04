package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.Objects;

public record GATransactionRunResult(
        String unitId,
        GATransactionSnapshot snapshot,
        GATransactionHandoffMetadata handoff
) {
    public boolean success() {
        return snapshot.state() == GATransactionState.SEALED
                && handoff.action() == GATransactionHandoffAction.NONE;
    }
}
