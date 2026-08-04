package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Loader-facing promotion/publishing guard. It decides only whether optimization is allowed.
 */
public record GAPublishingGuardDecision(GAPublishingGuardAction action, List<String> reasons) {
    public static GAPublishingGuardDecision evaluate(
            boolean loaderGuardPresent,
            boolean ticketSystemOwnedByVanilla,
            boolean chunkStatusReady,
            boolean hasLifecycleConflict
    ) {
        List<String> reasons = null;
        if (!loaderGuardPresent) {
            reasons = addReason(reasons, "loader_guard_missing");
        }
        if (!ticketSystemOwnedByVanilla) {
            reasons = addReason(reasons, "ticket_system_not_vanilla_owned");
        }
        if (!chunkStatusReady) {
            reasons = addReason(reasons, "chunk_status_not_ready");
        }
        if (hasLifecycleConflict) {
            reasons = addReason(reasons, "lifecycle_conflict");
        }
        if (reasons == null) {
            return new GAPublishingGuardDecision(GAPublishingGuardAction.ALLOW_OPTIMIZED, List.of());
        }
        GAPublishingGuardAction action = hasLifecycleConflict
                ? GAPublishingGuardAction.FALLBACK_SERIAL
                : GAPublishingGuardAction.DEFER_TO_VANILLA;
        return new GAPublishingGuardDecision(action, reasons);
    }

    public boolean optimizedAllowed() {
        return action == GAPublishingGuardAction.ALLOW_OPTIMIZED;
    }

    private static List<String> addReason(List<String> reasons, String reason) {
        if (reasons == null) {
            reasons = new ArrayList<>(4);
        }
        reasons.add(reason);
        return reasons;
    }
}
