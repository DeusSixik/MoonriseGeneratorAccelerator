package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.Objects;

public record GATransactionHandoffMetadata(
        String unitId,
        GATransactionState state,
        GATransactionHandoffAction action,
        String reason,
        String quarantineKey,
        String exceptionClass
) {
    public GATransactionHandoffMetadata {
        unitId = normalizeUnitId(unitId);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(action, "action");
    }

    public static GATransactionHandoffMetadata none(String unitId) {
        return new GATransactionHandoffMetadata(
                unitId,
                GATransactionState.SEALED,
                GATransactionHandoffAction.NONE,
                null,
                null,
                null
        );
    }

    public static GATransactionHandoffMetadata fromSnapshot(String unitId, GATransactionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return switch (snapshot.state()) {
            case SEALED -> none(unitId);
            case DOWNGRADED -> new GATransactionHandoffMetadata(
                    unitId,
                    snapshot.state(),
                    GATransactionHandoffAction.SERIAL_FALLBACK,
                    snapshot.reason(),
                    null,
                    null
            );
            case ABORTED -> new GATransactionHandoffMetadata(
                    unitId,
                    snapshot.state(),
                    GATransactionHandoffAction.QUARANTINE_AND_SERIAL_FALLBACK,
                    snapshot.reason(),
                    quarantineKey(unitId, snapshot.reason()),
                    exceptionClass(snapshot.reason())
            );
            case OPEN -> new GATransactionHandoffMetadata(
                    unitId,
                    snapshot.state(),
                    GATransactionHandoffAction.SERIAL_FALLBACK,
                    "transaction left open",
                    null,
                    null
            );
        };
    }

    public boolean requiresSerialFallback() {
        return action == GATransactionHandoffAction.SERIAL_FALLBACK
                || action == GATransactionHandoffAction.QUARANTINE_AND_SERIAL_FALLBACK;
    }

    public boolean requiresQuarantine() {
        return action == GATransactionHandoffAction.QUARANTINE_AND_SERIAL_FALLBACK;
    }

    private static String normalizeUnitId(String unitId) {
        if (unitId == null || unitId.isBlank()) {
            return "unknown";
        }
        return unitId.trim();
    }

    private static String quarantineKey(String unitId, String reason) {
        String normalizedReason = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
        return normalizeUnitId(unitId) + "|" + normalizedReason;
    }

    private static String exceptionClass(String reason) {
        if (reason == null || !reason.startsWith("failure: ")) {
            return null;
        }
        String failure = reason.substring("failure: ".length());
        int separator = failure.indexOf(':');
        return separator < 0 ? failure : failure.substring(0, separator);
    }
}
