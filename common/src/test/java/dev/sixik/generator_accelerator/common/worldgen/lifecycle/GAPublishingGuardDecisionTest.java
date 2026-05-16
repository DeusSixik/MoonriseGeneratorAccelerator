package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAPublishingGuardDecisionTest {
    @Test
    void allowsOptimizedPublishingOnlyWhenAllGuardsPass() {
        GAPublishingGuardDecision decision = GAPublishingGuardDecision.evaluate(true, true, true, false);

        assertEquals(GAPublishingGuardAction.ALLOW_OPTIMIZED, decision.action());
        assertTrue(decision.optimizedAllowed());
        assertEquals(List.of(), decision.reasons());
    }

    @Test
    void defersToVanillaWhenLoaderOrStatusGuardMissing() {
        GAPublishingGuardDecision decision = GAPublishingGuardDecision.evaluate(false, true, false, false);

        assertEquals(GAPublishingGuardAction.DEFER_TO_VANILLA, decision.action());
        assertFalse(decision.optimizedAllowed());
        assertEquals(List.of("loader_guard_missing", "chunk_status_not_ready"), decision.reasons());
    }

    @Test
    void fallsBackSerialWhenLifecycleConflictExists() {
        GAPublishingGuardDecision decision = GAPublishingGuardDecision.evaluate(true, false, true, true);

        assertEquals(GAPublishingGuardAction.FALLBACK_SERIAL, decision.action());
        assertFalse(decision.optimizedAllowed());
        assertEquals(List.of("ticket_system_not_vanilla_owned", "lifecycle_conflict"), decision.reasons());
    }
}
