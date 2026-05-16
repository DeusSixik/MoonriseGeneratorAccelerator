package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenSafetyTier;

import java.util.List;
import java.util.Map;

public record WorldgenGeneratedPlan(
        String unitId,
        WorldgenOptimizationPattern pattern,
        WorldgenFastPathKind fastPathKind,
        WorldgenSafetyTier targetTier,
        List<WorldgenOptimizerGuard> guards,
        Map<String, String> attributes,
        int estimatedCost,
        String reason
) {
    public WorldgenGeneratedPlan {
        unitId = unitId == null ? "" : unitId;
        pattern = pattern == null ? WorldgenOptimizationPattern.NONE : pattern;
        fastPathKind = fastPathKind == null ? WorldgenFastPathKind.NONE : fastPathKind;
        targetTier = targetTier == null ? WorldgenSafetyTier.VANILLA_FALLBACK_DISABLED : targetTier;
        guards = guards == null ? List.of() : List.copyOf(guards);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        reason = reason == null ? "" : reason;
    }

    public boolean enabled() {
        return pattern != WorldgenOptimizationPattern.NONE && fastPathKind != WorldgenFastPathKind.NONE;
    }
}
